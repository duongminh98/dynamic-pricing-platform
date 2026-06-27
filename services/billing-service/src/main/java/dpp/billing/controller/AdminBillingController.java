package dpp.billing.controller;

import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PageResponse;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.service.BillingService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Administrator billing management endpoints.
 */
@RestController
@RequestMapping("/admin/billing")
public class AdminBillingController {

    private final BillingService billingService;

    public AdminBillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasRole('Administrator')")
    public PageResponse<InvoiceResponse> listInvoices(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return billingService.adminListInvoicesPaged(status, pageable);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasRole('Administrator')")
    public InvoiceResponse getInvoice(@PathVariable UUID id) {
        return billingService.adminGetInvoice(id);
    }

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("hasRole('Administrator')")
    public InvoiceResponse voidInvoice(@PathVariable UUID id) {
        return billingService.voidInvoice(id);
    }
}
