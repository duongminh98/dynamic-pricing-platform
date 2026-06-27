package dpp.order.controller;

import dpp.order.dto.CancelRequest;
import dpp.order.dto.PolicyDetailResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.PolicyStatus;
import dpp.order.service.PolicyLifecycleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Administrator policy management endpoints.
 *
 * <p>Allows admins to list, filter, view (full detail), and cancel any policy
 * regardless of customer ownership. Role enforcement via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/admin/policies")
public class AdminPolicyController {

    private final PolicyLifecycleService lifecycleService;

    public AdminPolicyController(PolicyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    @PreAuthorize("hasRole('Administrator')")
    public List<PolicyResponse> listAll(@RequestParam(required = false) PolicyStatus status) {
        if (status != null) {
            return lifecycleService.adminListPoliciesByStatus(status);
        }
        return lifecycleService.adminListAllPolicies();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('Administrator')")
    public PolicyDetailResponse getPolicyDetail(@PathVariable UUID id) {
        return lifecycleService.adminGetPolicyDetail(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('Administrator')")
    public PolicyResponse cancelPolicy(@PathVariable UUID id,
                                       @RequestBody(required = false) CancelRequest request) {
        OffsetDateTime cancelDate = request != null ? request.getCancelDate() : null;
        return lifecycleService.adminCancelPolicy(id, cancelDate);
    }
}
