package com.belunaro.tadmor.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The filter chain. The probes are open; the whole JSON API requires a
 * session and answers 401 with a JSON body without one (spec/api.md §1.1,
 * §3). Sessions over the shared tables are still to come, so for now no
 * request under /api/ is authenticated.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/healthz", "/readyz").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
					response.setStatus(HttpStatus.UNAUTHORIZED.value());
					response.setContentType(MediaType.APPLICATION_JSON_VALUE);
					response.getWriter().write("{\"error\":\"authentication required\"}");
				}))
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());
		return http.build();
	}
}
