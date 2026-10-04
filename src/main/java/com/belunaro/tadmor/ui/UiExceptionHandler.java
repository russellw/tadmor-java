package com.belunaro.tadmor.ui;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.DatabaseErrors;
import com.belunaro.tadmor.service.ServiceException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Refusals that escape a UI controller, chiefly an unknown record, as the
 * error page with the service's status: for 404 the not-found message
 * (spec/domain.md §13 G8). Refusals of actions are shown in place by the
 * controllers themselves (G5).
 */
@ControllerAdvice(basePackageClasses = UiExceptionHandler.class)
public class UiExceptionHandler {

	@ExceptionHandler
	public ModelAndView refused(ServiceException e, HttpServletResponse response) {
		return page(e.status(), e.getMessage(), response);
	}

	@ExceptionHandler
	public ModelAndView badPath(MethodArgumentTypeMismatchException e, HttpServletResponse response) {
		return page(HttpStatus.NOT_FOUND, "not found", response);
	}

	@ExceptionHandler
	public ModelAndView database(DataAccessException e, HttpServletResponse response) {
		ServiceException refusal = DatabaseErrors.refusal(e).orElseThrow(() -> e);
		return page(refusal.status(), refusal.getMessage(), response);
	}

	private static ModelAndView page(HttpStatus status, String message, HttpServletResponse response) {
		response.setStatus(status.value());
		ModelAndView page = new ModelAndView("error", Map.of("status", status.value(), "message", message));
		page.setStatus(status);
		return page;
	}
}
