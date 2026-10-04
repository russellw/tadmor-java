package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.service.UserService;
import com.belunaro.tadmor.service.UserService.NewUser;
import com.belunaro.tadmor.service.UserService.UserRecord;
import com.belunaro.tadmor.service.UserService.UserUpdate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration (spec/api.md §5.1). Administrators only: SecurityConfig
 * answers 403 to anyone else before a request gets here.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService users;

	public UserController(UserService users) {
		this.users = users;
	}

	public record PasswordReset(String password) {
	}

	@GetMapping
	public List<UserRecord> list() {
		return users.list();
	}

	@GetMapping("/{id}")
	public UserRecord get(@PathVariable Id id) {
		return users.get(id.value());
	}

	@PostMapping
	public ResponseEntity<Map<String, Integer>> create(@RequestBody NewUser in) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", users.create(in)));
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> update(@AuthenticationPrincipal CurrentUser caller, @PathVariable Id id,
			@RequestBody UserUpdate in) {
		users.update(caller, id.value(), in);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/password")
	public ResponseEntity<Void> setPassword(@PathVariable Id id, @RequestBody PasswordReset in) {
		users.setPassword(id.value(), in.password());
		return ResponseEntity.noContent().build();
	}
}
