package com.belunaro.tadmor.ui;

import java.util.List;

import com.belunaro.tadmor.security.CurrentUser;

import org.springframework.stereotype.Component;

/**
 * The sidebar, on every page, from which every screen is reachable
 * (spec/domain.md §13 G3). Administrator-only screens are left out for
 * everyone else (G4), as {@code ${@nav.groups(currentUser, path)}}.
 */
@Component("nav")
public class Navigation {

	public record Link(String text, String url, boolean active) {
	}

	public record Group(String title, List<Link> links) {
	}

	private record Item(String text, String url, boolean adminOnly) {
	}

	private static final List<Object[]> NAV = List.of(
			new Object[] { "Overview", List.of(item("Home", "/")) },
			new Object[] { "Sales", List.of(item("Invoices", "/sales-invoices"), item("Credit notes", "/sales-credit-notes"),
					item("Customer payments", "/customer-payments"), item("Sales orders", "/sales-orders")) },
			new Object[] { "Purchases", List.of(item("Bills", "/purchase-bills"), item("Supplier credits", "/purchase-credit-notes"),
					item("Supplier payments", "/supplier-payments"), item("Purchase orders", "/purchase-orders")) },
			new Object[] { "Inventory", List.of(item("Stock movements", "/stock-movements"),
					item("Inventory valuation", "/reports/inventory-valuation")) },
			new Object[] { "Accounting", List.of(item("Bank statements", "/bank-statements"),
					item("Exchange rates", "/exchange-rates"), item("Periods and year-end", "/periods")) },
			new Object[] { "Reports", List.of(item("Profit and loss", "/reports/profit-and-loss"),
					item("Balance sheet", "/reports/balance-sheet"), item("Cash flow", "/reports/cash-flow"),
					item("Trial balance", "/reports/trial-balance"), item("AR aging", "/reports/ar-aging"),
					item("AP aging", "/reports/ap-aging")) },
			new Object[] { "Master data", List.of(item("Organizations", "/organizations"), item("Customers", "/customers"),
					item("Suppliers", "/suppliers"), item("Products", "/products"), item("Chart of accounts", "/accounts"),
					item("Tax codes", "/tax-codes"), item("Payment terms", "/payment-terms"), item("Warehouses", "/warehouses")) },
			new Object[] { "Administration", List.of(new Item("Users", "/users", true), item("Settings", "/settings")) });

	private static Item item(String text, String url) {
		return new Item(text, url, false);
	}

	@SuppressWarnings("unchecked")
	public List<Group> groups(CurrentUser user, String path) {
		boolean admin = user != null && user.isAdmin();
		return NAV.stream().map(g -> new Group((String) g[0], ((List<Item>) g[1]).stream()
				.filter(i -> admin || !i.adminOnly())
				.map(i -> new Link(i.text(), i.url(), i.url().equals("/") ? "/".equals(path)
						: path != null && (path.equals(i.url()) || path.startsWith(i.url() + "/"))))
				.toList())).toList();
	}
}
