package kr.fishingstory.common.logging;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiRequestLogFilter extends OncePerRequestFilter {

	private static final org.slf4j.Logger log = LoggerFactory.getLogger(ApiRequestLogFilter.class);
	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		String previous = MDC.get("requestId");
		long started = System.nanoTime();
		boolean completed = false;
		MDC.put("requestId", requestId);
		response.setHeader("X-Request-Id", requestId);
		try {
			chain.doFilter(request, response);
			completed = true;
		} finally {
			try {
				// Only server-selected controller metadata is logged. Never copy URI, headers, body or exception text.
				Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
				String operation = handler instanceof HandlerMethod method
						? method.getBeanType().getSimpleName() + "." + method.getMethod().getName() : "UNMAPPED";
				String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
				int status = completed ? response.getStatus() : 500;
				long durationMs = (System.nanoTime() - started) / 1_000_000;
				if (status >= 500) {
					log.warn("api_request requestId={} method={} operation={} status={} durationMs={}", requestId, method, operation, status, durationMs);
				} else {
					log.info("api_request requestId={} method={} operation={} status={} durationMs={}", requestId, method, operation, status, durationMs);
				}
			} finally {
				if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
			}
		}
	}
}
