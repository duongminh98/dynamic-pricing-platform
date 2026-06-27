package dpp.billing.controller;

import dpp.billing.dto.CreateRefundRequest;
import dpp.billing.dto.CompleteRefundRequest;
import dpp.billing.dto.RejectRefundRequest;
import dpp.billing.entity.RefundRequest;
import dpp.billing.entity.RefundStatus;
import dpp.billing.service.RefundService;
import dpp.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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
    public PageResponse<RefundRequest> listRefunds(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID policyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return PageResponse.from(refundService.listFiltered(status, customerId, policyId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"))));
    }

    @PostMapping
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest createRefund(@Valid @RequestBody CreateRefundRequest request) {
        return refundService.createRefund(request.getPolicyId(), request.getCustomerId(),
                request.getCreditId(), request.getAmountVnd(), request.getNote());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest completeRefund(@PathVariable UUID id,
                                        @Valid @RequestBody CompleteRefundRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        RefundRequest refund = refundService.completeRefund(id, request.getPaymentReference(), jwt.getSubject());
        if (request.getNote() != null && refund.getNote() == null) {
            refund.setNote(request.getNote());
        }
        return refund;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('Administrator')")
    public RefundRequest rejectRefund(@PathVariable UUID id,
                                      @Valid @RequestBody RejectRefundRequest request) {
        return refundService.rejectRefund(id, request.getReason());
    }
}
