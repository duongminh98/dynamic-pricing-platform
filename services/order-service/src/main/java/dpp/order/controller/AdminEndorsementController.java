package dpp.order.controller;

import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.dto.RejectRequest;
import dpp.order.service.PolicyLifecycleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator review of Material_Change endorsement requests (R23.9 / design §4.2).
 *
 * <p>Role enforcement is at the application layer via {@code @PreAuthorize}
 * (gateway JWT only verifies signature/exp, not roles). Only an Administrator can
 * approve or reject a pending endorsement; a Customer can never self-approve.
 */
@RestController
@RequestMapping("/admin/endorsements")
public class AdminEndorsementController {

    private final PolicyLifecycleService lifecycleService;

    public AdminEndorsementController(PolicyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
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
    public EndorsementRequestResponse extendDueDate(@PathVariable UUID id, @RequestBody Map<String, Integer> body) {
        int extraDays = body.getOrDefault("extra_days", 7);
        return lifecycleService.extendDueDate(id, extraDays);
    }

    @PostMapping("/{id}/cancel-policy")
    @PreAuthorize("hasRole('Administrator')")
    public PolicyResponse cancelPolicyFromEndorsement(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.cancelPolicyFromEndorsement(id, jwt.getSubject());
    }
}
