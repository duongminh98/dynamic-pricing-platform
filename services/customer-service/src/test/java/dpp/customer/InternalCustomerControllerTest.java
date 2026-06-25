package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.controller.InternalCustomerController;
import dpp.customer.entity.Account;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.repository.CustomerProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalCustomerControllerTest {

    @Test
    void getEmailReturnsEmailForExistingCustomer() {
        CustomerProfileRepository repo = mock(CustomerProfileRepository.class);
        UUID customerId = UUID.randomUUID();
        Account account = new Account();
        account.setEmail("customer@example.com");
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(customerId);
        profile.setAccount(account);

        when(repo.findById(customerId)).thenReturn(Optional.of(profile));

        InternalCustomerController controller = new InternalCustomerController(repo);
        Map<String, String> result = controller.getEmail(customerId);
        assertEquals("customer@example.com", result.get("email"));
    }

    @Test
    void getEmailRejectsUnknownCustomer() {
        CustomerProfileRepository repo = mock(CustomerProfileRepository.class);
        UUID customerId = UUID.randomUUID();
        when(repo.findById(customerId)).thenReturn(Optional.empty());

        InternalCustomerController controller = new InternalCustomerController(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getEmail(customerId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
