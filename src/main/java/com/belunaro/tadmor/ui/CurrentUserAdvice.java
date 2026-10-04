package com.belunaro.tadmor.ui;

import jakarta.servlet.http.HttpServletRequest;

import com.belunaro.tadmor.security.CurrentUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Puts the signed-in user and the request path in every UI page's model, for the header and sidebar. */
@ControllerAdvice(basePackageClasses = CurrentUserAdvice.class)
public class CurrentUserAdvice {

	@ModelAttribute("currentUser")
	public CurrentUser currentUser(@AuthenticationPrincipal CurrentUser user) {
		return user;
	}

	@ModelAttribute("path")
	public String path(HttpServletRequest request) {
		return request.getRequestURI();
	}
}
