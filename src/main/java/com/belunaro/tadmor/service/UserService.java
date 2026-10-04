package com.belunaro.tadmor.service;

import java.util.List;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.security.Sessions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (spec/api.md §5.1, spec/domain.md §12). Callers gate
 * it to administrators. Password hashes never leave this class; passwords
 * arrive only through create and reset.
 *
 * <p>Validation order follows the spec's precedence: a missing field (400)
 * before a malformed one (422), and both before existence (404) and
 * conflicts (409, a duplicate email from the unique index).
 */
@Service
public class UserService {

	static final int MIN_PASSWORD_LENGTH = 8;

	private final JdbcClient jdbc;
	private final PasswordEncoder encoder;
	private final Sessions sessions;

	public UserService(JdbcClient jdbc, PasswordEncoder encoder, Sessions sessions) {
		this.jdbc = jdbc;
		this.encoder = encoder;
		this.sessions = sessions;
	}

	/** The spec's {@code UserRecord}. */
	public record UserRecord(int id, String email, String fullName, boolean isActive, boolean isAdmin) {
	}

	/** The create body. On create is_active is ignored; users start active. */
	public record NewUser(String email, String fullName, String password, Boolean isAdmin) {
	}

	/** The update body: a full replacement, so an omitted boolean is false. */
	public record UserUpdate(String email, String fullName, Boolean isActive, Boolean isAdmin) {
	}

	private static final String SELECT = "SELECT id, email, full_name, is_active, is_admin FROM users";

	private static UserRecord row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new UserRecord(rs.getInt("id"), rs.getString("email"), rs.getString("full_name"),
				rs.getBoolean("is_active"), rs.getBoolean("is_admin"));
	}

	public List<UserRecord> list() {
		return jdbc.sql(SELECT + " ORDER BY email").query(UserService::row).list();
	}

	public UserRecord get(int id) {
		return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(UserService::row).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int create(NewUser in) {
		String email = trim(in.email());
		String fullName = trim(in.fullName());
		requireEmailAndName(email, fullName);
		requirePassword(in.password());
		return jdbc.sql("INSERT INTO users (email, full_name, password_hash, is_admin) VALUES (?, ?, ?, ?) RETURNING id")
				.params(email, fullName, encoder.encode(in.password()), isTrue(in.isAdmin()))
				.query(Integer.class)
				.single();
	}

	/**
	 * Replaces the user's record. Administrators may not deactivate or demote
	 * themselves, the guard against locking out the last administrator one
	 * click at a time.
	 */
	public void update(CurrentUser caller, int id, UserUpdate in) {
		String email = trim(in.email());
		String fullName = trim(in.fullName());
		requireEmailAndName(email, fullName);
		boolean active = isTrue(in.isActive());
		boolean admin = isTrue(in.isAdmin());
		if (caller.id() == id && !active) {
			throw ServiceException.unprocessable("you cannot deactivate your own account");
		}
		if (caller.id() == id && !admin) {
			throw ServiceException.unprocessable("you cannot remove your own administrator access");
		}
		int updated = jdbc.sql("UPDATE users SET email = ?, full_name = ?, is_active = ?, is_admin = ? WHERE id = ?")
				.params(email, fullName, active, admin, id)
				.update();
		if (updated == 0) {
			throw ServiceException.notFound();
		}
	}

	/** Resets the password and revokes all of the user's sessions. */
	@Transactional
	public void setPassword(int id, String password) {
		requirePassword(password);
		int updated = jdbc.sql("UPDATE users SET password_hash = ? WHERE id = ?")
				.params(encoder.encode(password), id)
				.update();
		if (updated == 0) {
			throw ServiceException.notFound();
		}
		sessions.revokeAll(id);
	}

	private static void requireEmailAndName(String email, String fullName) {
		if (email.isEmpty()) {
			throw ServiceException.badRequest("email is required");
		}
		if (fullName.isEmpty()) {
			throw ServiceException.badRequest("full_name is required");
		}
		if (!email.contains("@")) {
			throw ServiceException.unprocessable("email must contain @");
		}
	}

	private static void requirePassword(String password) {
		if (password == null || password.isEmpty()) {
			throw ServiceException.badRequest("password is required");
		}
		if (password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH) {
			throw ServiceException.unprocessable("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
		}
	}

	private static String trim(String s) {
		return s == null ? "" : s.strip();
	}

	private static boolean isTrue(Boolean b) {
		return b != null && b;
	}
}
