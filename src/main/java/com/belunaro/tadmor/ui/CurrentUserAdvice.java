package com.belunaro.tadmor.ui;

import com.belunaro.tadmor.security.CurrentUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Puts the signed-in user in every UI page's model, for the page header. */
@ControllerAdvice(basePackageClasses = CurrentUserAdvice.class)
public class CurrentUserAdvice {

	@ModelAttribute("currentUser")
	public CurrentUser currentUser(@AuthenticationPrincipal CurrentUser user) {
		return user;
	}
}
