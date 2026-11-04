package com.portfolio.ticketing.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Teaches Spring MVC to fill controller parameters of type {@link AuthenticatedUser}.
 *
 * <p>An <b>argument resolver</b> is the hook MVC calls for each controller parameter it does not already
 * understand. Registered in {@code config/WebConfig}. If it is ever asked for a user when nobody is logged in
 * (which the security rules should make impossible), it fails with 403 rather than passing null along.
 */
public class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return AuthenticatedUser.from(jwtAuthentication.getToken());
        }
        throw new AccessDeniedException("Authentication required");
    }
}
