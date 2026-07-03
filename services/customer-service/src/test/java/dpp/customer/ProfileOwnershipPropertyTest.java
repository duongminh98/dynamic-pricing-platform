package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.controller.CustomerController;
import dpp.customer.entity.Account;
import dpp.customer.service.ProfileService;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Property 13: data-ownership isolation. GET /customers/me resolves strictly by
 * the caller's JWT subject, so a caller only ever sees their own account; a
 * different subject resolves to a different account; an unknown subject is
 * rejected. Requirements: R18.3.
 */
@Tag("Feature: dynamic-pricing-platform, Property 13")
class ProfileOwnershipPropertyTest {

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        when(jwt.getClaimAsString("email")).thenReturn("user@example.com");
        return jwt;
    }

    private Account accountFor(UUID id, String subject) {
        Account a = new Account();
        a.setAccountId(id);
        a.setKeycloakSubject(subject);
        a.setEmail("user@example.com");
        return a;
    }

    @Property(tries = 100)
    void getMeResolvesCallerOwnAccount(@ForAll long seed) {
        String subject = "subject-" + seed;
        UUID id = UUID.randomUUID();
        ProfileService profileService = mock(ProfileService.class);
        when(profileService.ensureAccount(subject, "user@example.com", null)).thenReturn(accountFor(id, subject));

        CustomerController controller = new CustomerController(profileService);
        Map<String, Object> result = controller.getMe(jwtFor(subject));
        assertEquals(id, result.get("accountId"));
        assertEquals(subject, result.get("keycloakSubject"));
    }

    @Property(tries = 100)
    void getMeNeverReturnsAnotherSubjectsAccount(@ForAll long seed) {
        String owner = "owner-" + seed;
        String caller = "caller-" + seed;
        UUID ownerId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        ProfileService profileService = mock(ProfileService.class);
        when(profileService.ensureAccount(owner, "user@example.com", null)).thenReturn(accountFor(ownerId, owner));
        when(profileService.ensureAccount(caller, "user@example.com", null)).thenReturn(accountFor(callerId, caller));

        CustomerController controller = new CustomerController(profileService);
        Map<String, Object> result = controller.getMe(jwtFor(caller));
        // The caller's own account is returned, never the other owner's.
        assertEquals(callerId, result.get("accountId"));
        assertNotEquals(ownerId, result.get("accountId"));
    }

    @Test
    void getMePropagatesBootstrapFailure() {
        ProfileService profileService = mock(ProfileService.class);
        when(profileService.ensureAccount("ghost", "user@example.com", null))
                .thenThrow(new ServiceException(ErrorCode.BAD_REQUEST, "Email claim is required", null));

        CustomerController controller = new CustomerController(profileService);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getMe(jwtFor("ghost")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
