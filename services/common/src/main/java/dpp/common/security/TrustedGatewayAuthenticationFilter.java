package dpp.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TrustedGatewayAuthenticationFilter extends OncePerRequestFilter {

    public static final String SUBJECT_HEADER = "X-Authenticated-User-Sub";
    public static final String ROLES_HEADER = "X-Authenticated-User-Roles";
    public static final String ISSUER_HEADER = "X-Authenticated-User-Issuer";
    public static final String CLIENT_ID_HEADER = "X-Authenticated-Client-Id";
    public static final String EMAIL_HEADER = "X-Authenticated-User-Email";
    public static final String NAME_B64_HEADER = "X-Authenticated-User-Name-B64";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String subject = trimToNull(request.getHeader(SUBJECT_HEADER));
        if (subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            List<String> roles = parseRoles(request.getHeader(ROLES_HEADER));
            Jwt jwt = buildPrincipal(subject, roles, request.getHeader(ISSUER_HEADER),
                    request.getHeader(CLIENT_ID_HEADER), request.getHeader(EMAIL_HEADER),
                    decodeName(request.getHeader(NAME_B64_HEADER)));
            Collection<GrantedAuthority> authorities = roles.stream()
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .map(SimpleGrantedAuthority::new)
                    .map(GrantedAuthority.class::cast)
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        }
        filterChain.doFilter(request, response);
    }

    private Jwt buildPrincipal(String subject, List<String> roles, String issuer, String clientId, String email, String name) {
        Instant now = Instant.now();
        String issuerClaim = trimToNull(issuer) != null ? trimToNull(issuer) : "gateway";
        String clientIdClaim = trimToNull(clientId) != null ? trimToNull(clientId) : "";
        String emailClaim = trimToNull(email);
        String nameClaim = trimToNull(name);
        Jwt.Builder builder = Jwt.withTokenValue("gateway-authenticated")
                .header("alg", "none")
                .subject(subject)
                .issuer(issuerClaim)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", roles))
                .claim("azp", clientIdClaim);
        // The original Keycloak JWT is consumed at the gateway; the email is forwarded as a
        // trusted header so downstream services can JIT-provision the account by email.
        if (emailClaim != null) {
            builder.claim("email", emailClaim);
            builder.claim("preferred_username", emailClaim);
        }
        // Display name (Keycloak `name` claim) rides along the same trusted-header path.
        if (nameClaim != null) {
            builder.claim("name", nameClaim);
        }
        return builder.build();
    }

    private List<String> parseRoles(String header) {
        String roles = trimToNull(header);
        if (roles == null) {
            return List.of();
        }
        return Arrays.stream(roles.split(","))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The display name arrives base64-encoded (UTF-8) so diacritics survive the Latin-1
     *  HTTP header hop. Decode defensively — a malformed value simply yields no name. */
    private String decodeName(String base64Value) {
        String value = trimToNull(base64Value);
        if (value == null) {
            return null;
        }
        try {
            return trimToNull(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
