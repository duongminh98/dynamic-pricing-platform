package dpp.billing.controller;

import dpp.billing.dto.InvoiceResponse;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.service.BillingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public List<InvoiceResponse> listInvoices(@RequestParam(required = false) InvoiceStatus status) {
        if (status != null) {
            return billingService.adminListInvoicesByStatus(status);
        }
        return billingService.adminListAllInvoices();
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
