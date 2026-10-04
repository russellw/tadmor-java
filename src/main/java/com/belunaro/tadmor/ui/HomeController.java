package com.belunaro.tadmor.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The home screen. A placeholder until the dashboard of spec/domain.md §13.2. */
@Controller
public class HomeController {

	@GetMapping("/")
	public String home() {
		return "home";
	}
}
