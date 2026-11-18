package kr.fishingstory.auth.infrastructure.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

final class AccessTokenGenerationFilter extends OncePerRequestFilter {
	private final UserQueryMapper users;
	private final SecurityErrorHandler errors;

	AccessTokenGenerationFilter(UserQueryMapper users, SecurityErrorHandler errors) {
		this.users = users;
		this.errors = errors;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
			var jwt = authentication.getToken();
			Long issued = switch (jwt.getClaims().get(AccessTokenIssuer.GENERATION_CLAIM)) {
				case null -> 0L; // Access issued before V15.
				case Integer value -> value.longValue();
				case Long value -> value;
				default -> null;
			};
			long current;
			try {
				// Resource handlers still enforce existence, ACTIVE status and role.
				current = users.findAccessTokenGeneration(AuthenticatedUser.id(jwt)).orElse(0L);
			} catch (DataAccessException unavailable) {
				errors.write(response, ErrorCode.SERVICE_UNAVAILABLE);
				return;
			} catch (ApiException invalid) {
				errors.commence(request, response, new BadCredentialsException("Invalid access subject"));
				return;
			}
			if (issued == null || issued != current) {
				errors.commence(request, response, new BadCredentialsException("Invalid access generation"));
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
