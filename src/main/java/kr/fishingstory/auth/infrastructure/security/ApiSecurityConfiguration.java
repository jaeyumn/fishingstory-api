package kr.fishingstory.auth.infrastructure.security;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class ApiSecurityConfiguration {
	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, SecurityErrorHandler errors, Environment environment,
			UserQueryMapper users) throws Exception {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("role");
		authorities.setAuthorityPrefix("ROLE_");
		var authentication = new JwtAuthenticationConverter();
		authentication.setJwtGrantedAuthoritiesConverter(authorities);
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> {
					requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
							.requestMatchers(HttpMethod.POST, "/api/auth/signup/email-code", "/api/auth/signup/email-code/verify",
									"/api/auth/signup/email", "/api/auth/login/email", "/api/auth/refresh",
									"/api/auth/password-reset/email-code", "/api/auth/password-reset/email-code/verify",
									"/api/auth/password-reset", "/api/auth/withdrawal-cancel/email-code",
									"/api/auth/withdrawal-cancel/email-code/verify", "/api/auth/withdrawal-cancel").permitAll()
							.requestMatchers(HttpMethod.GET, "/api/terms/current", "/api/terms/upcoming", "/actuator/health").permitAll();
					if (environment.acceptsProfiles(Profiles.of("dev", "test")) && !environment.acceptsProfiles(Profiles.of("prod"))) {
						requests.requestMatchers(HttpMethod.GET, "/test", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
					}
					requests.requestMatchers("/api/admin/**").hasRole("ADMIN");
					requests.anyRequest().authenticated();
				})
				.exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
						.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.addFilterAfter(new AccessTokenGenerationFilter(users, errors), BearerTokenAuthenticationFilter.class);
		return http.build();
	}
}
