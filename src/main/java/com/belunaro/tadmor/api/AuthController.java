package com.belunaro.tadmor.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.security.Login;
import com.belunaro.tadmor.security.Sessions;
import com.belunaro.tadmor.service.ServiceException;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login, logout, and the current user (spec/api.md §3). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final Login login;
	private final Sessions sessions;

	public AuthController(Login login, Sessions sessions) {
		this.login = login;
		this.sessions = sessions;
	}

	public record LoginRequest(String email, String password) {
	}

	@PostMapping("/login")
	public CurrentUser login(@RequestBody LoginRequest in, HttpServletRequest request, HttpServletResponse response) {
		String email = in.email() == null ? "" : in.email().strip();
		String password = in.password() == null ? "" : in.password();
		if (email.isEmpty() || password.isEmpty()) {
			throw ServiceException.badRequest("email and password are required");
		}
		CurrentUser user = login.authenticate(email, password)
				.orElseThrow(() -> ServiceException.unauthorized("invalid email or password"));
		Sessions.setCookie(request, response, sessions.create(user.id()));
		return user;
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		Sessions.token(request).ifPresent(sessions::revoke);
		Sessions.clearCookie(request, response);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	public CurrentUser me(@AuthenticationPrincipal CurrentUser user) {
		return user;
	}
}
