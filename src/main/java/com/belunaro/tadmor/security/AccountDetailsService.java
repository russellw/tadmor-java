package com.belunaro.tadmor.security;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Login credentials from the shared users table, for Spring Security's
 * DaoAuthenticationProvider.
 *
 * <p>Only active users are found. A deactivated user is therefore "not found"
 * rather than "disabled", so the provider runs the same dummy password check
 * for it as for an unknown email, and all three login failures take the same
 * time (spec/api.md §3).
 *
 * <p>Emails are citext, so the lookup is case-insensitive, but only with the
 * explicit cast: pgjdbc sends strings as varchar, and citext = varchar
 * resolves to case-sensitive text equality.
 */
@Service
public class AccountDetailsService implements UserDetailsService {

	private final JdbcClient jdbc;

	public AccountDetailsService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public UserDetails loadUserByUsername(String email) {
		return jdbc.sql("""
				SELECT id, email, full_name, is_admin, password_hash
				FROM users WHERE email = ?::citext AND is_active""")
				.param(email.strip())
				.query((rs, n) -> new Account(
						new CurrentUser(rs.getInt("id"), rs.getString("email"), rs.getString("full_name"),
								rs.getBoolean("is_admin")),
						rs.getString("password_hash")))
				.optional()
				.orElseThrow(() -> new UsernameNotFoundException("no active user with that email"));
	}

	static List<GrantedAuthority> authorities(CurrentUser user) {
		return user.isAdmin()
				? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
				: List.of(new SimpleGrantedAuthority("ROLE_USER"));
	}

	/** A user with the stored hash, used only while a login is checked. */
	public record Account(CurrentUser user, String passwordHash) implements UserDetails {

		@Override
		public Collection<? extends GrantedAuthority> getAuthorities() {
			return authorities(user);
		}

		@Override
		public String getPassword() {
			return passwordHash;
		}

		@Override
		public String getUsername() {
			return user.email();
		}

		@Override
		public String toString() {
			return "Account[" + user + "]";
		}
	}
}
