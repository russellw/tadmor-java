package com.belunaro.tadmor.security;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Checks a login's credentials, for the JSON API and the UI's login form
 * alike. An unknown email, a wrong password, and a deactivated user all come
 * back empty, after the same password-hashing work (AccountDetailsService).
 */
@Service
public class Login {

	private static final Logger log = LoggerFactory.getLogger(Login.class);

	private final AuthenticationManager authentication;

	public Login(AuthenticationManager authentication) {
		this.authentication = authentication;
	}

	public Optional<CurrentUser> authenticate(String email, String password) {
		try {
			var result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
			CurrentUser user = ((AccountDetailsService.Account) result.getPrincipal()).user();
			log.info("login {}", user.email());
			return Optional.of(user);
		} catch (AuthenticationException e) {
			return Optional.empty();
		}
	}
}
