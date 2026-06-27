package dpp.billing.controller;

import dpp.billing.entity.RefundRequest;
import dpp.billing.entity.RefundStatus;
import dpp.billing.service.RefundService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator refund management endpoints (design §8).
 */
@RestController
@RequestMapping("/admin/refunds")
public class AdminRefundController {

    private final RefundService refundService;

    public AdminRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @GetMapping
    @PreAuthorize("hasRole('Administrator')")
    public List<RefundRequest> listRefunds(@RequestParam(required = false) RefundStatus status) {
        if (status != null) {
            return refundService.listByStatus(status);
        }
        return refundService.listAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest createRefund(@RequestBody Map<String, Object> body) {
        UUID policyId = UUID.fromString(String.valueOf(body.get("policy_id")));
        UUID customerId = UUID.fromString(String.valueOf(body.get("customer_id")));
        UUID creditId = body.get("credit_id") != null
                ? UUID.fromString(String.valueOf(body.get("credit_id"))) : null;
        long amountVnd = Long.parseLong(String.valueOf(body.get("amount_vnd")));
        String note = body.get("note") != null ? String.valueOf(body.get("note")) : null;
        return refundService.createRefund(policyId, customerId, creditId, amountVnd, note);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest completeRefund(@PathVariable UUID id, @RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal Jwt jwt) {
        String paymentReference = body.get("payment_reference");
        String note = body.get("note");
        String completedBy = jwt.getSubject();
        RefundRequest refund = refundService.completeRefund(id, paymentReference, completedBy);
        if (note != null && refund.getNote() == null) {
            refund.setNote(note);
        }
        return refund;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest rejectRefund(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        return refundService.rejectRefund(id, reason);
    }
}
