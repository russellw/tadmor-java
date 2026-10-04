package com.belunaro.tadmor.api;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * A synthetic key from a request path. Declaring a path variable as Id makes
 * anything but a positive integer a 400, before any lookup (spec/api.md §1.4):
 * "abc", "0", "-3", "1.5", and numbers too large to be a key.
 */
public record Id(int value) {

	/** Registered with Spring MVC as a bean, so {@code @PathVariable Id id} works. */
	@Component
	public static class FromString implements Converter<String, Id> {

		@Override
		public Id convert(String source) {
			int id = Integer.parseInt(source);
			if (id <= 0) {
				throw new IllegalArgumentException("id must be positive");
			}
			return new Id(id);
		}
	}
}
