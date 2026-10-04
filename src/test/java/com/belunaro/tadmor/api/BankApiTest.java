package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Bank reconciliation (spec/api.md §5.13, spec/domain.md §8). */
class BankApiTest extends PostingTest {

	private int cashAccount() throws Exception {
		return create("/api/accounts", "{\"code\":\"C" + System.nanoTime() + "\",\"name\":\"Bank\",\"account_type\":\"asset\","
				+ "\"is_postable\":true,\"is_cash\":true}");
	}

	/** Posts a customer payment of the amount into the bank account, on the date; returns its journal line there. */
	private int deposit(int bank, int customer, String date, String amount) throws Exception {
		int pay = create("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + date
				+ "\",\"currency_code\":\"USD\",\"amount\":\"" + amount + "\",\"deposit_account_id\":" + bank + "}");
		int entry = post("customer-payments", pay);
		return jdbc.sql("SELECT id FROM journal_lines WHERE journal_entry_id = ? AND account_id = ?").params(entry, bank)
				.query(Integer.class).single();
	}

	private int statement(int bank, String opening, String closing) throws Exception {
		return create("/api/bank-statements", "{\"account_id\":" + bank + ",\"statement_date\":\"" + y + "-03-31\","
				+ "\"opening_balance\":\"" + opening + "\",\"closing_balance\":\"" + closing + "\",\"reference\":\"ST-1\"}");
	}

	private int line(int statement, String date, String amount) throws Exception {
		return create("/api/bank-statements/" + statement + "/lines", "{\"txn_date\":\"" + date
				+ "\",\"description\":\"x\",\"amount\":\"" + amount + "\"}");
	}

	@Test
	void statementsBelongToCashAccounts() throws Exception {
		int bank = cashAccount();
		assertJsonError(postJson("/api/bank-statements", "{\"account_id\":" + account("asset") + ",\"statement_date\":\"" + y
				+ "-03-31\",\"opening_balance\":\"0\",\"closing_balance\":\"0\"}", session), 422);
		assertJsonError(postJson("/api/bank-statements", "{\"account_id\":" + bank + ",\"statement_date\":\"" + y
				+ "-03-31\",\"opening_balance\":\"0\"}", session), 400);

		int id = statement(bank, "100", "150");
		JsonNode s = read("/api/bank-statements/" + id);
		assertThat(s.get("status").asString()).isEqualTo("open");
		assertThat(s.get("line_count").asInt()).isZero();
		assertThat(dec(s.get("difference"))).isEqualTo("-50");
		line(id, y + "-03-05", "20");
		line(id, y + "-03-06", "30");
		s = read("/api/bank-statements/" + id);
		assertThat(s.get("line_count").asInt()).isEqualTo(2);
		assertThat(dec(s.get("lines_total"))).isEqualTo("50");
		assertThat(dec(s.get("difference"))).isEqualTo("0");
		assertJsonError(postJson("/api/bank-statements/" + id + "/lines", "{\"txn_date\":\"" + y + "-03-07\",\"description\":\"z\",\"amount\":\"0\"}", session), 422);
		assertJsonError(get("/api/bank-statements/999999/lines", session), 404);
	}

	@Test
	void csvImportIsAllOrNothing() throws Exception {
		int id = statement(cashAccount(), "0", "0");
		HttpResponse<String> r = postJson("/api/bank-statements/" + id + "/import", "{\"csv\":\"date,description,amount,reference\\n"
				+ y + "-03-01,\\\"Deposit, cash\\\",100.50,DEP-1\\n\\n" + y + "-03-02,Fee,-2\\n\"}", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(json(r.body()).get("imported").asInt()).isEqualTo(2);
		JsonNode lines = read("/api/bank-statements/" + id + "/lines");
		assertThat(lines.get(0).get("description").asString()).isEqualTo("Deposit, cash");
		assertThat(lines.get(0).get("reference").asString()).isEqualTo("DEP-1");
		assertThat(lines.get(1).get("line_no").asInt()).isEqualTo(2);
		assertThat(lines.get(1).get("reference").isNull()).isTrue();

		for (String bad : new String[] { y + "-03-03,Ok,1\\n" + y + "-13-01,Bad date,1", y + "-03-03,,1", y + "-03-03,Zero,0.00",
				y + "-03-03,Words,ten", y + "-03-03,Too,1,many,fields", "header,only,here", y + "-03-03,\\\"open,1" }) {
			assertJsonError(postJson("/api/bank-statements/" + id + "/import", "{\"csv\":\"" + bad + "\"}", session), 422);
		}
		assertJsonError(postJson("/api/bank-statements/" + id + "/import", "{\"csv\":\"\"}", session), 400);
		assertThat(read("/api/bank-statements/" + id + "/lines")).as("bad imports add nothing").hasSize(2);
	}

	@Test
	void matchingAndReconciling() throws Exception {
		int bank = cashAccount();
		int customer = customer(account("asset"));
		int early = deposit(bank, customer, y + "-03-01", "50");
		int late = deposit(bank, customer, y + "-03-20", "50");
		int hundred = deposit(bank, customer, y + "-03-10", "100");
		int id = statement(bank, "0", "200");
		int lineA = line(id, y + "-03-19", "50");
		int lineB = line(id, y + "-03-11", "100");
		int lineC = line(id, y + "-03-02", "50");

		assertThat(read("/api/bank-statements/" + id + "/candidates")).hasSize(3);
		String match = "/api/bank-statement-lines/" + lineB + "/match";
		assertJsonError(postJson(match, "{}", session), 400);
		assertJsonError(postJson(match, "{\"journal_line_id\":" + early + "}", session), 422); // 50 is not 100
		assertJsonError(postJson(match, "{\"journal_line_id\":999999}", session), 422);
		assertThat(postJson(match, "{\"journal_line_id\":" + hundred + "}", session).statusCode()).isEqualTo(204);
		assertJsonError(postJson(match, "{\"journal_line_id\":" + hundred + "}", session), 409);
		// The same amount on another statement: the journal line already backs a statement line.
		int other = line(statement(bank, "0", "100"), y + "-03-10", "100");
		assertJsonError(postJson("/api/bank-statement-lines/" + other + "/match", "{\"journal_line_id\":" + hundred + "}", session), 409);
		assertThat(read("/api/bank-statements/" + id + "/candidates")).hasSize(2);

		assertJsonError(postJson("/api/bank-statements/" + id + "/reconcile", "", session), 422);

		// Auto-match takes the nearest entry date for each line, in line order.
		HttpResponse<String> r = postJson("/api/bank-statements/" + id + "/auto-match", "", session);
		assertThat(json(r.body()).get("matched").asInt()).isEqualTo(2);
		JsonNode lines = read("/api/bank-statements/" + id + "/lines");
		assertThat(lines.get(0).get("journal_line_id").asInt()).isEqualTo(late);
		assertThat(lines.get(2).get("journal_line_id").asInt()).isEqualTo(early);
		assertThat(lines.get(0).get("entry_date").asString()).isEqualTo(y + "-03-20");
		assertThat(read("/api/bank-statements/" + id).get("matched_count").asInt()).isEqualTo(3);

		// A journal line matched on a statement cannot be unposted.
		int pay = jdbc.sql("SELECT p.id FROM customer_payments p JOIN journal_lines jl ON jl.journal_entry_id = p.journal_entry_id "
				+ "WHERE jl.id = ?").param(early).query(Integer.class).single();
		assertJsonError(postJson("/api/customer-payments/" + pay + "/unpost", "", adminSession), 409);

		assertThat(postJson("/api/bank-statements/" + id + "/reconcile", "", session).statusCode()).isEqualTo(204);
		assertThat(read("/api/bank-statements/" + id).get("status").asString()).isEqualTo("reconciled");
		assertJsonError(postJson("/api/bank-statement-lines/" + lineC + "/unmatch", "", session), 409);
		assertJsonError(sendJson("DELETE", "/api/bank-statement-lines/" + lineC, "", session), 409);
		assertJsonError(sendJson("DELETE", "/api/bank-statements/" + id, "", session), 409);
		assertJsonError(postJson("/api/bank-statements/" + id + "/reconcile", "", session), 409);

		assertJsonError(postJson("/api/bank-statements/" + id + "/reopen", "", session), 403);
		assertThat(postJson("/api/bank-statements/" + id + "/reopen", "", adminSession).statusCode()).isEqualTo(204);
		assertJsonError(postJson("/api/bank-statements/" + id + "/reopen", "", adminSession), 409);
		assertThat(postJson("/api/bank-statement-lines/" + lineC + "/unmatch", "", session).statusCode()).isEqualTo(204);
		assertThat(postJson("/api/bank-statement-lines/" + lineC + "/unmatch", "", session).statusCode()).isEqualTo(204);

		// Unbalanced: everything matched, but opening + lines <> closing.
		assertThat(postJson("/api/bank-statement-lines/" + lineC + "/match", "{\"journal_line_id\":" + early + "}", session)
				.statusCode()).isEqualTo(204);
		assertThat(sendJson("PUT", "/api/bank-statements/" + id, "{\"account_id\":" + bank + ",\"statement_date\":\"" + y
				+ "-03-31\",\"opening_balance\":\"0\",\"closing_balance\":\"999\"}", session).statusCode()).isEqualTo(204);
		assertJsonError(postJson("/api/bank-statements/" + id + "/reconcile", "", session), 422);
		assertJsonError(sendJson("PUT", "/api/bank-statements/" + id, "{\"account_id\":" + cashAccount() + ",\"statement_date\":\""
				+ y + "-03-31\",\"opening_balance\":\"0\",\"closing_balance\":\"200\"}", session), 422);
	}
}
