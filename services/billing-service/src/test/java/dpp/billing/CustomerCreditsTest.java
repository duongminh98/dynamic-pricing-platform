package dpp.billing;

import dpp.billing.controller.BillingController;
import dpp.billing.dto.CreditWalletItem;
import dpp.billing.dto.CustomerCreditsResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.VnpayService;
import dpp.common.security.CustomerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for GET /billing/credits — customer credit wallet (read-only).
 *
 * T1: Customer with credits across multiple policies → all returned, sorted, total correct.
 * T2: Credit from policy A applied to invoice of policy B → applied_to_policy_id = B.
 * T3: Customer with no credits → empty list, total = 0.
 * T4: Only returns credits for the given customerId (no leakage).
 * T2b: Invoice cache avoids duplicate lookups.
 * T5: Controller derives customerId from JWT subject, does not accept customer_id param.
 * T6: Batch application loading — single query for all credits on the page.
 * T7: Pagination metadata in response.
 */
class CustomerCreditsTest {

    private PremiumCreditRepository creditRepo;
    private CreditApplicationRepository appRepo;
    private InvoiceRepository invoiceRepo;
    private ProcessedEventRepository processedEventRepo;
    private CreditService creditService;

    @BeforeEach
    void setUp() {
        creditRepo = mock(PremiumCreditRepository.class);
        appRepo = mock(CreditApplicationRepository.class);
        invoiceRepo = mock(InvoiceRepository.class);
        processedEventRepo = mock(ProcessedEventRepository.class);
        creditService = new CreditService(creditRepo, appRepo, invoiceRepo, processedEventRepo);
    }

    private Pageable defaultPageable() {
        return PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "createdAt"));
    }

    private PremiumCredit credit(UUID creditId, UUID policyId, UUID customerId,
                                 long original, long remaining, CreditStatus status) {
        PremiumCredit c = new PremiumCredit();
        c.setCreditId(creditId);
        c.setPolicyId(policyId);
        c.setCustomerId(customerId);
        c.setSourceEndorsementId(UUID.randomUUID());
        c.setOriginalAmountVnd(original);
        c.setRemainingAmountVnd(remaining);
        c.setStatus(status);
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }

    private CreditApplication application(UUID appId, UUID creditId, UUID invoiceId, long amount) {
        CreditApplication a = new CreditApplication();
        a.setApplicationId(appId);
        a.setCreditId(creditId);
        a.setAppliedToInvoiceId(invoiceId);
        a.setAmountAppliedVnd(amount);
        a.setCreatedAt(OffsetDateTime.now());
        return a;
    }

    private Invoice invoice(UUID invoiceId, UUID orderId, UUID policyId) {
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(orderId);
        inv.setPolicyId(policyId);
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.paid);
        inv.setCreatedAt(OffsetDateTime.now());
        return inv;
    }

    private void mockPage(UUID customerId, List<PremiumCredit> credits) {
        Page<PremiumCredit> page = new PageImpl<>(credits, defaultPageable(), credits.size());
        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(eq(customerId), any(Pageable.class)))
                .thenReturn(page);
    }

    private void mockTotalRemaining(UUID customerId, List<PremiumCredit> activeCredits) {
        when(creditRepo.findByCustomerIdAndStatusInOrderByCreatedAtAsc(eq(customerId), any()))
                .thenReturn(activeCredits);
    }

    // ── T1: Multiple credits across policies, sorted, total correct ──
    @Test
    void t1_multipleCreditsAcrossPolicies_sortedAndTotalCorrect() {
        UUID customerId = UUID.randomUUID();
        UUID policyA = UUID.randomUUID();
        UUID policyB = UUID.randomUUID();

        PremiumCredit c1 = credit(UUID.randomUUID(), policyA, customerId,
                500_000, 200_000, CreditStatus.partially_applied);
        c1.setCreatedAt(OffsetDateTime.now().minusDays(2));
        PremiumCredit c2 = credit(UUID.randomUUID(), policyB, customerId,
                300_000, 300_000, CreditStatus.open);
        c2.setCreatedAt(OffsetDateTime.now().minusDays(1));
        PremiumCredit c3 = credit(UUID.randomUUID(), policyA, customerId,
                100_000, 0, CreditStatus.exhausted);
        c3.setCreatedAt(OffsetDateTime.now());

        mockPage(customerId, List.of(c1, c2, c3));
        mockTotalRemaining(customerId, List.of(c1, c2));
        when(appRepo.findByCreditIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId, defaultPageable());

        assertEquals(3, resp.getCredits().size());
        assertEquals(500_000L, resp.getTotalRemainingVnd());
        assertTrue(resp.getCredits().get(0).getCreatedAt()
                .isBefore(resp.getCredits().get(1).getCreatedAt()));
    }

    // ── T2: Credit from policy A applied to invoice of policy B ──
    @Test
    void t2_crossPolicyApplication_resolvesAppliedToPolicyId() {
        UUID customerId = UUID.randomUUID();
        UUID policyA = UUID.randomUUID();
        UUID policyB = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        UUID invoiceIdB = UUID.randomUUID();

        PremiumCredit c = credit(creditId, policyA, customerId,
                800_000, 0, CreditStatus.exhausted);

        CreditApplication app = application(UUID.randomUUID(), creditId, invoiceIdB, 800_000);
        Invoice invB = invoice(invoiceIdB, UUID.randomUUID(), policyB);

        mockPage(customerId, List.of(c));
        mockTotalRemaining(customerId, List.of());
        when(appRepo.findByCreditIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(app));
        when(invoiceRepo.findById(invoiceIdB)).thenReturn(Optional.of(invB));

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId, defaultPageable());

        assertEquals(1, resp.getCredits().size());
        CreditWalletItem item = resp.getCredits().get(0);
        assertEquals(policyA, item.getPolicyId());
        assertEquals(1, item.getApplications().size());
        assertEquals(policyB, item.getApplications().get(0).getAppliedToPolicyId());
        assertEquals(invoiceIdB, item.getApplications().get(0).getAppliedToInvoiceId());
        assertEquals(800_000L, item.getApplications().get(0).getAmountAppliedVnd());
        assertEquals(0L, resp.getTotalRemainingVnd());
    }

    // ── T3: No credits → empty list, total = 0 ──
    @Test
    void t3_noCredits_returnsEmptyListAndZeroTotal() {
        UUID customerId = UUID.randomUUID();
        mockPage(customerId, List.of());
        mockTotalRemaining(customerId, List.of());

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId, defaultPageable());

        assertTrue(resp.getCredits().isEmpty());
        assertEquals(0L, resp.getTotalRemainingVnd());
    }

    // ── T4: Only returns credits for the given customerId (no leakage) ──
    @Test
    void t4_onlyReturnsCreditsForGivenCustomer() {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();

        PremiumCredit creditA = credit(UUID.randomUUID(), UUID.randomUUID(), customerA,
                100_000, 100_000, CreditStatus.open);

        mockPage(customerA, List.of(creditA));
        mockTotalRemaining(customerA, List.of(creditA));
        mockPage(customerB, List.of());
        mockTotalRemaining(customerB, List.of());

        CustomerCreditsResponse respA = creditService.getCustomerCredits(customerA, defaultPageable());
        assertEquals(1, respA.getCredits().size());
        assertEquals(100_000L, respA.getTotalRemainingVnd());

        CustomerCreditsResponse respB = creditService.getCustomerCredits(customerB, defaultPageable());
        assertTrue(respB.getCredits().isEmpty());
        assertEquals(0L, respB.getTotalRemainingVnd());

        verify(creditRepo).findByCustomerIdOrderByCreatedAtAsc(eq(customerA), any(Pageable.class));
        verify(creditRepo).findByCustomerIdOrderByCreatedAtAsc(eq(customerB), any(Pageable.class));
    }

    // ── T2b: Invoice cache avoids N+1 when same invoice appears in multiple credits ──
    @Test
    void t2b_invoiceCacheAvoidsDuplicateLookups() {
        UUID customerId = UUID.randomUUID();
        UUID policyA = UUID.randomUUID();
        UUID policyB = UUID.randomUUID();
        UUID credit1Id = UUID.randomUUID();
        UUID credit2Id = UUID.randomUUID();
        UUID sharedInvoiceId = UUID.randomUUID();

        PremiumCredit c1 = credit(credit1Id, policyA, customerId,
                500_000, 0, CreditStatus.exhausted);
        PremiumCredit c2 = credit(credit2Id, policyB, customerId,
                300_000, 0, CreditStatus.exhausted);

        CreditApplication app1 = application(UUID.randomUUID(), credit1Id, sharedInvoiceId, 500_000);
        CreditApplication app2 = application(UUID.randomUUID(), credit2Id, sharedInvoiceId, 300_000);
        Invoice sharedInv = invoice(sharedInvoiceId, UUID.randomUUID(), policyB);

        mockPage(customerId, List.of(c1, c2));
        mockTotalRemaining(customerId, List.of());
        when(appRepo.findByCreditIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(app1, app2));
        when(invoiceRepo.findById(sharedInvoiceId)).thenReturn(Optional.of(sharedInv));

        creditService.getCustomerCredits(customerId, defaultPageable());

        verify(invoiceRepo, times(1)).findById(eq(sharedInvoiceId));
    }

    // ── T6: Batch application loading — single query for all credits on the page ──
    @Test
    void t6_batchApplicationLoading_singleQuery() {
        UUID customerId = UUID.randomUUID();
        UUID credit1Id = UUID.randomUUID();
        UUID credit2Id = UUID.randomUUID();
        UUID credit3Id = UUID.randomUUID();

        PremiumCredit c1 = credit(credit1Id, UUID.randomUUID(), customerId, 100_000, 0, CreditStatus.exhausted);
        PremiumCredit c2 = credit(credit2Id, UUID.randomUUID(), customerId, 200_000, 0, CreditStatus.exhausted);
        PremiumCredit c3 = credit(credit3Id, UUID.randomUUID(), customerId, 300_000, 300_000, CreditStatus.open);

        mockPage(customerId, List.of(c1, c2, c3));
        mockTotalRemaining(customerId, List.of(c3));
        when(appRepo.findByCreditIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        creditService.getCustomerCredits(customerId, defaultPageable());

        verify(appRepo, times(1)).findByCreditIdInOrderByCreatedAtAsc(any());
        verify(appRepo, never()).findByCreditIdOrderByCreatedAtAsc(any());
    }

    // ── T7: Pagination metadata in response ──
    @Test
    void t7_paginationMetadataInResponse() {
        UUID customerId = UUID.randomUUID();
        PremiumCredit c1 = credit(UUID.randomUUID(), UUID.randomUUID(), customerId, 100_000, 100_000, CreditStatus.open);

        Page<PremiumCredit> page = new PageImpl<>(List.of(c1), defaultPageable(), 50);
        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(eq(customerId), any(Pageable.class)))
                .thenReturn(page);
        mockTotalRemaining(customerId, List.of(c1));
        when(appRepo.findByCreditIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId, defaultPageable());

        assertEquals(0, resp.getPage());
        assertEquals(20, resp.getSize());
        assertEquals(50L, resp.getTotalElements());
        assertEquals(3, resp.getTotalPages());
    }

    // ── T5: Controller derives customerId from JWT subject, no customer_id param ──
    @Test
    void t5_controllerDerivesCustomerIdFromJwtSubject() {
        String subject = "keycloak-sub-abc-123";
        UUID derivedCustomerId = CustomerId.fromSubject(subject);

        CreditService mockCreditService = mock(CreditService.class);
        BillingController controller = new BillingController(
                mock(BillingService.class), mockCreditService, mock(VnpayService.class), false);

        CustomerCreditsResponse expected = new CustomerCreditsResponse();
        expected.setTotalRemainingVnd(0);
        expected.setCredits(List.of());
        when(mockCreditService.getCustomerCredits(eq(derivedCustomerId), any(Pageable.class)))
                .thenReturn(expected);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);

        CustomerCreditsResponse result = controller.getCustomerCredits(jwt, 0, 20);

        // Service called with UUID derived from JWT subject via CustomerId.fromSubject
        verify(mockCreditService).getCustomerCredits(eq(derivedCustomerId), any(Pageable.class));
        assertNotNull(result);
        assertEquals(0L, result.getTotalRemainingVnd());
    }
}
