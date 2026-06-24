package dpp.common.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the canonical customer_id derivation (design 5.1, BR-10).
 * Guarantees every service maps the same Keycloak subject to the same UUID.
 */
class CustomerIdTest {

    @Test
    void sameSubjectAlwaysYieldsSameId() {
        String subject = "1f3c8a2e-0000-4000-8000-000000000001";
        assertEquals(CustomerId.fromSubject(subject), CustomerId.fromSubject(subject),
                "Derivation must be deterministic for a given subject");
    }

    @Test
    void matchesLegacyDerivationUsedAcrossServices() {
        String subject = "demo-customer-subject";
        UUID expected = UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, CustomerId.fromSubject(subject),
                "Canonical helper must match nameUUIDFromBytes(sub) used by order/claims/notification");
    }

    @Test
    void differentSubjectsYieldDifferentIds() {
        assertNotEquals(CustomerId.fromSubject("subject-a"), CustomerId.fromSubject("subject-b"));
    }

    @Test
    void nullSubjectRejected() {
        assertThrows(IllegalArgumentException.class, () -> CustomerId.fromSubject(null));
    }
}
