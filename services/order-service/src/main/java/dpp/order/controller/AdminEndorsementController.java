package dpp.order.controller;

import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.ExtendDueDateRequest;
import dpp.order.dto.PageResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.dto.RejectRequest;
import dpp.order.entity.EndorsementStatus;
import dpp.order.service.PolicyLifecycleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Administrator review of Material_Change endorsement requests (R23.9 / design §4.2).
 */
@RestController
@RequestMapping("/admin/endorsements")
public class AdminEndorsementController {

    private final PolicyLifecycleService lifecycleService;

    public AdminEndorsementController(PolicyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    @PreAuthorize("hasRole('Administrator')")
    public PageResponse<EndorsementRequestResponse> listEndorsements(
            @RequestParam(required = false) EndorsementStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID policyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return lifecycleService.adminEndorsementQueuePaged(status, customerId, policyId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasRole('Administrator')")
    public List<EndorsementRequestResponse> reviewQueue() {
        return lifecycleService.endorsementReviewQueue();
    }

    @GetMapping("/pending-payment-queue")
    @PreAuthorize("hasRole('Administrator')")
    public List<EndorsementRequestResponse> pendingPaymentQueue() {
        return lifecycleService.pendingPaymentQueue();
    }

    @GetMapping("/voided")
    @PreAuthorize("hasRole('Administrator')")
    public List<EndorsementRequestResponse> voidedEndorsements() {
        return lifecycleService.voidedEndorsements();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('Administrator')")
    public EndorsementRequestResponse getDetail(@PathVariable UUID id) {
        return lifecycleService.adminGetEndorsementDetail(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('Administrator')")
    public EndorsementRequestResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.approveEndorsement(id, jwt.getSubject());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('Administrator')")
    public EndorsementRequestResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.rejectEndorsement(id, request.getReason(), jwt.getSubject());
    }

    @PostMapping("/{id}/extend-due-date")
    @PreAuthorize("hasRole('Administrator')")
    public EndorsementRequestResponse extendDueDate(@PathVariable UUID id,
                                                     @Valid @RequestBody ExtendDueDateRequest request) {
        return lifecycleService.extendDueDate(id, request.getExtraDays());
    }

    @PostMapping("/{id}/cancel-policy")
    @PreAuthorize("hasRole('Administrator')")
    public PolicyResponse cancelPolicyFromEndorsement(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.cancelPolicyFromEndorsement(id, jwt.getSubject());
    }
}
