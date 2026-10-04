package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.decimal;
import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock movements (spec/api.md §5.12): a signed quantity of a product into
 * or out of a warehouse, at a unit cost. The schema refuses a quantity whose
 * sign disagrees with the type, a zero quantity, a negative cost, an
 * unknown type, and a product that is not tracked and active (422); it
 * computes total_cost = round(quantity × unit_cost, 4) (spec/domain.md §2).
 *
 * <p>A movement is posted exactly when it has a journal entry. Only receipts
 * and issues post, in the base currency (domain §4.3). Movements produced by
 * order fulfilment cannot be edited, since they draw the order down, but
 * may be deleted while unposted (domain §6.4).
 */
@Service
public class StockService {

	private final JdbcClient jdbc;
	private final Journal journal;

	public StockService(JdbcClient jdbc, Journal journal) {
		this.jdbc = jdbc;
		this.journal = journal;
	}

	public record StockMovement(int id, int productId, int warehouseId, String movementDate, String movementType,
			String status, String quantity, String unitCost, String totalCost, String reference, String notes,
			Integer journalEntryId, String sourceType) {
	}

	/** The movement date defaults to today (UTC), and the unit cost to 0. */
	public record MovementInput(Integer productId, Integer warehouseId, String movementType, String movementDate,
			String quantity, String unitCost, String reference, String notes) {
		public MovementInput {
			movementType = trim(movementType);
			movementDate = movementDate == null || movementDate.isBlank() ? null : movementDate.strip();
			quantity = trim(quantity);
			unitCost = decimal(unitCost, "0");
		}
	}

	private static final String SELECT = """
			SELECT id, product_id, warehouse_id, movement_date::text AS movement_date, movement_type,
			    CASE WHEN journal_entry_id IS NULL THEN 'draft' ELSE 'posted' END AS status,
			    quantity::text AS quantity, unit_cost::text AS unit_cost, total_cost::text AS total_cost,
			    reference, notes, journal_entry_id, source_type
			FROM stock_movements""";

	/** Newest first, then by id descending. */
	public List<StockMovement> list() {
		return jdbc.sql(SELECT + " ORDER BY movement_date DESC, id DESC").query(StockMovement.class).list();
	}

	public StockMovement get(int id) {
		return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(StockMovement.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int create(MovementInput in) {
		validate(in);
		return jdbc.sql("""
				INSERT INTO stock_movements (product_id, warehouse_id, movement_type, movement_date, quantity, unit_cost,
				    reference, notes)
				VALUES (:productId, :warehouseId, :movementType, COALESCE(CAST(:movementDate AS date), current_date),
				    CAST(:quantity AS numeric), CAST(:unitCost AS numeric), :reference, :notes)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	@Transactional
	public void update(int id, MovementInput in) {
		validate(in);
		Unposted m = requireUnposted(id);
		if (m.sourceType() != null) {
			throw ServiceException.conflict("stock movement " + id + " was produced by order fulfilment and cannot be edited");
		}
		jdbc.sql("""
				UPDATE stock_movements SET product_id = :productId, warehouse_id = :warehouseId,
				    movement_type = :movementType, movement_date = COALESCE(CAST(:movementDate AS date), current_date),
				    quantity = CAST(:quantity AS numeric), unit_cost = CAST(:unitCost AS numeric),
				    reference = :reference, notes = :notes
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update();
	}

	@Transactional
	public void delete(int id) {
		requireUnposted(id);
		jdbc.sql("DELETE FROM stock_movements WHERE id = ?").param(id).update();
	}

	/**
	 * Posts the movement in the base currency (domain §4.2, §4.3): it must
	 * exist (404) and be unposted (409); it must be a receipt or an issue,
	 * with a non-zero cost and the product's accounts, and a receipt needs a
	 * postable, active credit account (typically GRNI); then an open period
	 * (all 422). An issue debits COGS and credits inventory by
	 * abs(total_cost); a receipt debits inventory and credits the credit
	 * account by total_cost.
	 */
	@Transactional
	public int post(int id, Integer creditAccountId) {
		record Movement(String movementType, String date, Integer journalEntryId, Integer inventoryAccountId,
				Integer cogsAccountId, String totalCost, boolean hasCost) {
		}
		Movement m = jdbc.sql("""
				SELECT sm.movement_type, sm.movement_date::text AS date, sm.journal_entry_id, p.inventory_account_id,
				    p.cogs_account_id, abs(sm.total_cost)::text AS total_cost, sm.total_cost <> 0 AS has_cost
				FROM stock_movements sm JOIN products p ON p.id = sm.product_id
				WHERE sm.id = ? FOR UPDATE OF sm""").param(id).query(Movement.class).optional()
				.orElseThrow(ServiceException::notFound);
		if (m.journalEntryId() != null) {
			throw ServiceException.conflict("stock movement " + id + " is already posted");
		}
		boolean issue = m.movementType().equals("issue");
		if (!issue && !m.movementType().equals("receipt")) {
			throw ServiceException.unprocessable("only receipts and issues post; this movement is " + m.movementType());
		}
		if (!m.hasCost()) {
			throw ServiceException.unprocessable("stock movement " + id + " has nothing to post: its total cost is 0");
		}
		if (m.inventoryAccountId() == null || (issue && m.cogsAccountId() == null)) {
			throw ServiceException.unprocessable("the product has no " + (issue ? "COGS or " : "") + "inventory account");
		}
		int debit;
		int credit;
		if (issue) {
			debit = m.cogsAccountId();
			credit = m.inventoryAccountId();
		} else {
			boolean usable = creditAccountId != null && jdbc.sql("SELECT is_postable AND is_active FROM accounts WHERE id = ?")
					.param(creditAccountId).query(Boolean.class).optional().orElse(false);
			if (!usable) {
				throw ServiceException.unprocessable("a receipt needs a credit_account_id naming a postable, active account");
			}
			debit = m.inventoryAccountId();
			credit = creditAccountId;
		}
		int period = journal.periodFor(m.date());
		String base = jdbc.sql("SELECT base_currency FROM gl_settings").query(String.class).single();
		int entry = journal.createEntry(m.date(), period, base, issue ? "Inventory issue" : "Inventory receipt", null);
		jdbc.sql("""
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				VALUES (:entry, 1, :debit, CAST(:cost AS numeric), 0, :debitMemo, CAST(:cost AS numeric), 0),
				       (:entry, 2, :credit, 0, CAST(:cost AS numeric), :creditMemo, 0, CAST(:cost AS numeric))""")
				.param("entry", entry).param("debit", debit).param("credit", credit).param("cost", m.totalCost())
				.param("debitMemo", issue ? "Cost of goods sold" : "Inventory")
				.param("creditMemo", issue ? "Inventory" : "Goods received").update();
		jdbc.sql("UPDATE stock_movements SET journal_entry_id = ?, period_id = ? WHERE id = ?").params(entry, period, id)
				.update();
		return entry;
	}

	/**
	 * Unposts the movement (administrators only): reverses its entry and
	 * unlinks it, leaving the quantity record untouched (domain §4.4).
	 */
	@Transactional
	public int unpost(int id) {
		// A record, not a bare column: optional() would take a NULL entry for a missing row.
		Integer entry = jdbc.sql("SELECT journal_entry_id, source_type FROM stock_movements WHERE id = ? FOR UPDATE")
				.param(id).query(Unposted.class).optional().orElseThrow(ServiceException::notFound).journalEntryId();
		if (entry == null) {
			throw ServiceException.conflict("stock movement " + id + " is not posted");
		}
		int reversal = journal.reverse(entry);
		jdbc.sql("UPDATE stock_movements SET journal_entry_id = NULL, period_id = NULL WHERE id = ?").param(id).update();
		return reversal;
	}

	private record Unposted(Integer journalEntryId, String sourceType) {
	}

	/** Locks the movement; 404 if it does not exist, 409 if it is posted. */
	private Unposted requireUnposted(int id) {
		Unposted m = jdbc.sql("SELECT journal_entry_id, source_type FROM stock_movements WHERE id = ? FOR UPDATE").param(id)
				.query(Unposted.class).optional().orElseThrow(ServiceException::notFound);
		if (m.journalEntryId() != null) {
			throw ServiceException.conflict("stock movement " + id + " is posted");
		}
		return m;
	}

	/** 400 for a missing product, warehouse, type, or quantity. */
	private static void validate(MovementInput in) {
		if (in.productId() == null || in.productId() <= 0) {
			throw ServiceException.badRequest("product_id is required");
		}
		if (in.warehouseId() == null || in.warehouseId() <= 0) {
			throw ServiceException.badRequest("warehouse_id is required");
		}
		require(in.movementType(), "movement_type");
		require(in.quantity(), "quantity");
	}
}
