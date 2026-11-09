package io.github.moyalshoham.runway.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-site request forgery guard: every state-changing API call must carry the
 * {@value #HEADER} header. A browser only sends a custom header cross-origin after a CORS preflight,
 * which this API never approves, so a hostile page cannot import data or create companies through
 * the user's browser. Plain HTML forms and "simple" requests cannot set it at all.
 */
@Component
@Order(1)
public class CsrfHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Runway-Client";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        boolean api = request.getRequestURI().startsWith("/api/");
        if (api && !SAFE.contains(request.getMethod()) && (header == null || header.isBlank())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403," + "\"detail\":\"Missing "
                            + HEADER + " header\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
