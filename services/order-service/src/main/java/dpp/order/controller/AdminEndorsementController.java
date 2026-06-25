package dpp.order.controller;

import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.RejectRequest;
import dpp.order.service.PolicyLifecycleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
}
