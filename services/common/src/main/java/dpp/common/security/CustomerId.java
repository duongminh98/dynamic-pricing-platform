package dpp.common.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Canonical, system-wide derivation of the business {@code customer_id}.
 *
 * <p>The platform uses logical cross-service identifiers (no foreign keys across
 * service boundaries, see design 5.x). The single source of truth for a
 * customer's identity is the Keycloak subject ({@code sub}) carried in the JWT.
 * Every service MUST derive {@code customer_id} the same way so that
 * quote -> order -> policy -> claim -> notification all reference one identity
 * (design 5.1, BR-10, R24.3).</p>
 */
public final class CustomerId {

    private CustomerId() {
    }

    /**
     * Deterministically derive the canonical {@code customer_id} from a Keycloak subject.
     *
     * @param keycloakSubject the JWT {@code sub} claim; must not be null
     * @return a stable UUID identical across all services for the same subject
     */
    public static UUID fromSubject(String keycloakSubject) {
        if (keycloakSubject == null) {
            throw new IllegalArgumentException("keycloakSubject must not be null");
        }
        return UUID.nameUUIDFromBytes(keycloakSubject.getBytes(StandardCharsets.UTF_8));
    }
}
