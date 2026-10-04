package com.belunaro.tadmor.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.security.Login;
import com.belunaro.tadmor.security.Sessions;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The login screen, the only page shown without a session, and sign-out
 * (spec/domain.md §13 G1, G2). It uses the same credential check and
 * sessions as the JSON API.
 */
@Controller
public class LoginController {

	private final Login login;
	private final Sessions sessions;

	public LoginController(Login login, Sessions sessions) {
		this.login = login;
		this.sessions = sessions;
	}

	@GetMapping("/login")
	public String form(@AuthenticationPrincipal CurrentUser user) {
		return user != null ? "redirect:/" : "login";
	}

	@PostMapping("/login")
	public String submit(@RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		email = email.strip();
		var user = email.isEmpty() || password.isEmpty() ? java.util.Optional.<CurrentUser>empty()
				: login.authenticate(email, password);
		if (user.isEmpty()) {
			model.addAttribute("email", email);
			model.addAttribute("error", email.isEmpty() || password.isEmpty()
					? "Enter your email and password."
					: "Invalid email or password.");
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			return "login";
		}
		Sessions.setCookie(request, response, sessions.create(user.get().id()));
		return "redirect:/";
	}

	@PostMapping("/logout")
	public String logout(HttpServletRequest request, HttpServletResponse response) {
		Sessions.token(request).ifPresent(sessions::revoke);
		Sessions.clearCookie(request, response);
		return "redirect:/login";
	}
}
