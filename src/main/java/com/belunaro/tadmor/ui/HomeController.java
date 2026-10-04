package com.belunaro.tadmor.ui;

import com.belunaro.tadmor.service.DashboardService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The home screen (spec/domain.md §13.2, H1 to H5). */
@Controller
public class HomeController {

	private final DashboardService dashboard;

	public HomeController(DashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/")
	public String home(Model model) {
		model.addAttribute("receivables", dashboard.outstanding(true));
		model.addAttribute("payables", dashboard.outstanding(false));
		model.addAttribute("counts", dashboard.counts());
		model.addAttribute("overdue", dashboard.overdueInvoices(10));
		model.addAttribute("dueSoon", dashboard.billsDueSoon());
		return "home";
	}
}
