package dpp.customer.controller;

import dpp.customer.dto.AdminCustomerResponse;
import dpp.customer.dto.PageResponse;
import dpp.customer.service.ProfileService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Administrator customer management endpoints.
 */
@RestController
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final ProfileService profileService;

    public AdminCustomerController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasRole('Administrator')")
    public PageResponse<AdminCustomerResponse> listCustomers(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "province", required = false) String province,
            @RequestParam(name = "locked", required = false) Boolean locked) {
        return profileService.adminListCustomers(q, province, locked, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('Administrator')")
    public AdminCustomerResponse getCustomer(@PathVariable UUID id) {
        return profileService.adminGetCustomer(id);
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasRole('Administrator')")
    public AdminCustomerResponse lockCustomer(@PathVariable UUID id, @RequestBody Map<String, Integer> body) {
        int hours = body.getOrDefault("hours", 24);
        return profileService.adminLockCustomer(id, hours);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('Administrator')")
    public AdminCustomerResponse unlockCustomer(@PathVariable UUID id) {
        return profileService.adminUnlockCustomer(id);
    }
}
