package dpp.billing.controller;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.service.BillingService;
import dpp.billing.service.VnpayService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.security.CustomerId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
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
    private final VnpayService vnpayService;
    private final boolean directPayEnabled;

    public BillingController(BillingService billingService, VnpayService vnpayService,
                             @Value("${dpp.billing.direct-pay.enabled:false}") boolean directPayEnabled) {
        this.billingService = billingService;
        this.vnpayService = vnpayService;
        this.directPayEnabled = directPayEnabled;
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        return billingService.createInvoice(request);
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

    @GetMapping("/invoices")
    public Object getInvoices(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(value = "order_id", required = false) UUID orderId,
                              @RequestParam(value = "policy_id", required = false) UUID policyId) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        if (orderId != null) {
            return billingService.getInvoiceByOrder(orderId, customerId);
        }
        if (policyId != null) {
            return billingService.getPolicyBilling(policyId, customerId);
        }
        throw new dpp.common.api.ServiceException(dpp.common.api.ErrorCode.BAD_REQUEST,
                "Either order_id or policy_id query parameter is required", null);
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
