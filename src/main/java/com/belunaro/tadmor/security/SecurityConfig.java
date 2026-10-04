package com.belunaro.tadmor.security;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The filter chain for the JSON API and the UI, which share one session
 * cookie (Sessions) instead of an HttpSession.
 *
 * <ul>
 * <li>The probes and the login and logout endpoints are open; the rest of
 * /api/ needs a session and answers 401 in JSON without one, or 403 for an
 * administrator-only endpoint (spec/api.md §1.4, §3).
 * <li>UI pages need a session too, and without one redirect to the login
 * page (spec/domain.md §13 G1).
 * <li>UI forms carry a CSRF token, kept in a cookie so no HttpSession is
 * created. The API is exempt: its session cookie is SameSite=Lax, as in
 * tadmor.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	private static final RequestMatcher API = request -> request.getRequestURI().startsWith("/api/");

	/** Only in the web server, not in the command-line tools (AddUser). */
	@Bean
	@ConditionalOnWebApplication
	public SecurityFilterChain filterChain(HttpSecurity http, SessionSecurityContextRepository contexts) throws Exception {
		http
				.securityContext(context -> context.securityContextRepository(contexts))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers("/healthz", "/readyz", "/error").permitAll()
						.requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
						.requestMatchers("/login").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.defaultAuthenticationEntryPointFor(SecurityConfig::unauthorized, API)
						.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), request -> true)
						.defaultAccessDeniedHandlerFor((request, response, e) -> writeError(response,
								HttpStatus.FORBIDDEN, "administrator access required"), API))
				.csrf(csrf -> csrf
						.csrfTokenRepository(new CookieCsrfTokenRepository())
						.ignoringRequestMatchers(API))
				.requestCache(cache -> cache.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());
		return http.build();
	}

	/**
	 * Spring Security's default delegating encoder: bcrypt for new hashes,
	 * with the scheme recorded in each hash so it can change later. The
	 * scheme is not contract (spec/domain.md §12).
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AccountDetailsService accounts, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(provider);
	}

	/** 401 for the API, clearing a session cookie that no longer names a session. */
	private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.core.AuthenticationException e) throws IOException {
		boolean stale = Sessions.token(request).isPresent();
		if (stale) {
			Sessions.clearCookie(request, response);
		}
		writeError(response, HttpStatus.UNAUTHORIZED, stale ? "session expired" : "authentication required");
	}

	private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		// The messages are fixed strings, so no JSON escaping is needed.
		response.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}
