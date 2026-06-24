package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.controller.CustomerController;
import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
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
 * Property 13: data-ownership isolation. A caller reading their own account
 * succeeds; reading another subject's account is rejected with FORBIDDEN_RESOURCE.
 * Requirements: R18.3.
 */
@Tag("Feature: dynamic-pricing-platform, Property 13")
class ProfileOwnershipPropertyTest {

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
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
    void ownerCanReadOwnAccount(@ForAll long seed) {
        String subject = "subject-" + seed;
        UUID id = UUID.randomUUID();
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(accountFor(id, subject)));

        CustomerController controller = new CustomerController(repo);
        Map<String, Object> result = controller.getCustomer(jwtFor(subject), id);
        assertEquals(id, result.get("accountId"));
    }

    @Property(tries = 100)
    void crossSubjectAccessRejected(@ForAll long seed) {
        String owner = "owner-" + seed;
        String intruder = "intruder-" + seed;
        UUID id = UUID.randomUUID();
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(accountFor(id, owner)));

        CustomerController controller = new CustomerController(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getCustomer(jwtFor(intruder), id));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
    }

    @Test
    void unknownAccountRejectedWithNotFound() {
        UUID id = UUID.randomUUID();
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findById(id)).thenReturn(Optional.empty());

        CustomerController controller = new CustomerController(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getCustomer(jwtFor("anyone"), id));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
