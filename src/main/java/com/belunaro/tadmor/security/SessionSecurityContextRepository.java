package com.belunaro.tadmor.security;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Loads each request's security context from its session cookie, in place of
 * Spring's HttpSession-backed repository. The lookup is deferred, so requests
 * that never consult the context (the probes) never touch the database.
 * Sessions are created only by an explicit login, never by saving a context.
 */
@Component
public class SessionSecurityContextRepository implements SecurityContextRepository {

	private final Sessions sessions;

	public SessionSecurityContextRepository(Sessions sessions) {
		this.sessions = sessions;
	}

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		Supplier<SecurityContext> load = () -> {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			Sessions.token(request).flatMap(sessions::user).ifPresent(user -> context.setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated(user, null, AccountDetailsService.authorities(user))));
			return context;
		};
		return new DeferredSecurityContext() {
			private SecurityContext context;

			@Override
			public SecurityContext get() {
				if (context == null) {
					context = load.get();
				}
				return context;
			}

			@Override
			public boolean isGenerated() {
				return get().getAuthentication() == null;
			}
		};
	}

	@Override
	@Deprecated
	@SuppressWarnings("removal")
	public SecurityContext loadContext(HttpRequestResponseHolder holder) {
		return loadDeferredContext(holder.getRequest()).get();
	}

	@Override
	public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		return Sessions.token(request).isPresent();
	}
}
