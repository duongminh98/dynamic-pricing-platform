package dpp.customer.controller;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.repository.CustomerProfileRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal service-to-service endpoints (NOT exposed through Kong). Used by
 * downstream services that need customer data without a customer JWT.
 */
@RestController
@RequestMapping("/internal/customers")
public class InternalCustomerController {

    private final CustomerProfileRepository profileRepository;

    public InternalCustomerController(CustomerProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /**
     * Resolve a customer's email address by their canonical customer_id (the
     * deterministic UUID derived from the Keycloak subject, design 5.1). Used by
     * notification-service to deliver email-channel notifications (R7.2).
     */
    @GetMapping("/{customerId}/email")
    public Map<String, String> getEmail(@PathVariable UUID customerId) {
        CustomerProfile profile = profileRepository.findById(customerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null));
        return Map.of("email", profile.getAccount().getEmail());
    }
}
