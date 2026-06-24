package dpp.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared JWT authentication converter that extracts Keycloak realm roles
 * from the nested {@code realm_access.roles} claim and maps them to Spring
 * Security authorities with the {@code ROLE_} prefix.
 *
 * <p>Spring's built-in {@code JwtGrantedAuthoritiesConverter} does not support
 * nested claim access via dot-notation, so {@code realm_access.roles} would
 * resolve to null and no authorities would be extracted. This converter
 * manually traverses the nested claim structure.</p>
 */
public final class KeycloakRoleConverter {

    private KeycloakRoleConverter() {
    }

    /**
     * Build a JWT authentication converter that maps realm roles to authorities.
     *
     * @return a converter suitable for
     *         {@code oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(...))}
     */
    public static Converter<Jwt, AbstractAuthenticationToken> create() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRoleConverter::extractAuthorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return Collections.emptyList();
        }
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}