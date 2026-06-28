package dpp.billing.controller;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.CustomerCreditsResponse;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.VnpayService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.security.CustomerId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/billing")
public class BillingController {

    private final BillingService billingService;
    private final CreditService creditService;
    private final VnpayService vnpayService;
    private final boolean directPayEnabled;

    public BillingController(BillingService billingService, CreditService creditService,
                             VnpayService vnpayService,
                             @Value("${dpp.billing.direct-pay.enabled:false}") boolean directPayEnabled) {
        this.billingService = billingService;
        this.creditService = creditService;
        this.vnpayService = vnpayService;
        this.directPayEnabled = directPayEnabled;
    }

    @PostMapping("/invoices/{id}/pay")
    public InvoiceResponse payInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!directPayEnabled) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE,
                    "Direct payment disabled; use VNPAY", null);
        }
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return billingService.payInvoiceAsCustomer(id, customerId);
    }

    @GetMapping("/invoices/by-order/{orderId}")
    public InvoiceResponse getInvoiceByOrder(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable UUID orderId) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return billingService.getInvoiceByOrder(orderId, customerId);
    }

    @GetMapping("/policies/{policyId}/billing")
    public PolicyBillingResponse getPolicyBilling(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID policyId) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return billingService.getPolicyBilling(policyId, customerId);
    }

    @GetMapping("/credits")
    @PreAuthorize("hasRole('Customer')")
    public CustomerCreditsResponse getCustomerCredits(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "page must be >= 0 and size must be > 0", null);
        }
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        size = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return creditService.getCustomerCredits(customerId, pageable);
    }

    // --- VNPAY endpoints (task 21.2-21.4, R33.2) ---

    /** Create a VNPAY payment URL for an invoice (task 21.2). Customer role. */
    @PostMapping("/invoices/{id}/payment-url")
    public Map<String, String> createPaymentUrl(@PathVariable UUID id,
                                                @AuthenticationPrincipal Jwt jwt,
                                                HttpServletRequest request) {
        return vnpayService.createPaymentUrl(id, request.getRemoteAddr());
    }

    /** VNPAY Return URL -- browser redirect, display only (task 21.3). Public. */
    @GetMapping("/vnpay/return")
    public Map<String, String> vnpayReturn(HttpServletRequest request) {
        Map<String, String> params = collectParams(request);
        return vnpayService.processReturn(params);
    }

    /** VNPAY IPN -- server-to-server, source of truth (task 21.3). Public. */
    @GetMapping("/vnpay/ipn")
    public Map<String, String> vnpayIpn(HttpServletRequest request) {
        Map<String, String> params = collectParams(request);
        return vnpayService.processIpn(params);
    }

    /** Query payment status by vnp_txn_ref (task 21.4). Customer role. */
    @GetMapping("/vnpay/status")
    public Map<String, String> vnpayStatus(@RequestParam("vnp_txn_ref") String txnRef) {
        return vnpayService.queryStatus(txnRef);
    }

    private Map<String, String> collectParams(HttpServletRequest request) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }
}
