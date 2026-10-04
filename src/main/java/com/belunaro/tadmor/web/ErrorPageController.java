package com.belunaro.tadmor.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Errors raised outside any controller, which the servlet container forwards
 * to /error: chiefly an unknown path or an unknown method. Under /api/ they
 * answer in the spec's JSON shape, with an unknown method treated as an
 * unknown path (spec/api.md §1.1). Elsewhere they render the error page,
 * which for 404 is the UI's not-found message (spec/domain.md §13 G8).
 * Replaces Spring Boot's BasicErrorController.
 */
@Controller
public class ErrorPageController implements ErrorController {

	private final JsonMapper json;

	public ErrorPageController(JsonMapper json) {
		this.json = json;
	}

	@RequestMapping("/error")
	public ModelAndView error(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		HttpStatus status = code instanceof Integer c ? HttpStatus.resolve(c) : null;
		if (status == null) {
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

		if (uri instanceof String path && path.startsWith("/api/")) {
			if (status == HttpStatus.METHOD_NOT_ALLOWED) {
				status = HttpStatus.NOT_FOUND;
			}
			response.setStatus(status.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			json.writeValue(response.getOutputStream(), Map.of("error", message(status)));
			return null;
		}

		ModelAndView page = new ModelAndView("error", Map.of("status", status.value(), "message", message(status)));
		page.setStatus(status);
		return page;
	}

	private static String message(HttpStatus status) {
		return switch (status) {
		case NOT_FOUND -> "not found";
		case INTERNAL_SERVER_ERROR -> "internal error";
		default -> status.getReasonPhrase().toLowerCase();
		};
	}
}
