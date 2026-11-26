package com.innowise.order_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TYPE_CLAIM = "type";
    private static final String ROLE_CLAIM = "role";

    private final JwtParser parser;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(authorization.substring(BEARER_PREFIX.length()));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String role = claims.get(ROLE_CLAIM, String.class);
            Long userId = Long.valueOf(claims.getSubject());
            if (userId > 0 && claims.getExpiration() != null && "access".equals(claims.get(TYPE_CLAIM, String.class))
                    && ("USER".equals(role) || "ADMIN".equals(role))) {
                OrderPrincipal principal = new OrderPrincipal(userId, "ADMIN".equals(role), token);
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken
                        .authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }
    }
}
