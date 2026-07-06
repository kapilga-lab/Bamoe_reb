package org.acme.security.jwt;

import java.io.IOException;

import org.acme.security.jwt.dto.UserDetailsJwt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Intercepts every API request, extracts the JWT from the {@code Authorization}
 * header, and populates {@link UserContextHolder} for the request lifecycle.
 *
 * <p>Registered automatically as a {@link Filter} bean; {@code @Order} gives it
 * the highest precedence so the identity is available to everything downstream.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String authHeader = httpRequest.getHeader("Authorization");

        // Case-insensitive "Bearer " check so "bearer", "BEARER", etc. are all accepted.
        if (authHeader != null && authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            try {
                UserDetailsJwt context = jwtUtil.extractUserDetailsFromToken(authHeader);
                UserContextHolder.setContext(context);
                log.debug("JWT user context established: user={} roles={}", context.getUsername(), context.getRoles());
            } catch (Exception e) {
                log.warn("Failed to establish user context from JWT", e);
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Clear context after request completion to prevent ThreadLocal leaks.
            UserContextHolder.clearContext();
        }
    }
}
