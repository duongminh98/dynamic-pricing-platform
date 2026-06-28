package dpp.billing;

import dpp.billing.dto.CreditWalletItem;
import dpp.billing.dto.CustomerCreditsResponse;
import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.billing.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * T4: Customer ID comes from JWT, no cross-customer leakage.
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

        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(List.of(c1, c2, c3));
        when(appRepo.findByCreditIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId);

        assertEquals(3, resp.getCredits().size());
        // total_remaining = 200_000 (partially_applied) + 300_000 (open) + 0 (exhausted)
        assertEquals(500_000L, resp.getTotalRemainingVnd());
        // Verify sorted by created_at ascending (repo already sorts)
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

        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(List.of(c));
        when(appRepo.findByCreditIdOrderByCreatedAtAsc(creditId))
                .thenReturn(List.of(app));
        when(invoiceRepo.findById(invoiceIdB))
                .thenReturn(Optional.of(invB));

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId);

        assertEquals(1, resp.getCredits().size());
        CreditWalletItem item = resp.getCredits().get(0);
        assertEquals(policyA, item.getPolicyId());
        assertEquals(1, item.getApplications().size());
        assertEquals(policyB, item.getApplications().get(0).getAppliedToPolicyId());
        assertEquals(invoiceIdB, item.getApplications().get(0).getAppliedToInvoiceId());
        assertEquals(800_000L, item.getApplications().get(0).getAmountAppliedVnd());
        // exhausted → not counted in total
        assertEquals(0L, resp.getTotalRemainingVnd());
    }

    // ── T3: No credits → empty list, total = 0 ──
    @Test
    void t3_noCredits_returnsEmptyListAndZeroTotal() {
        UUID customerId = UUID.randomUUID();
        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(List.of());

        CustomerCreditsResponse resp = creditService.getCustomerCredits(customerId);

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

        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerA))
                .thenReturn(List.of(creditA));
        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerB))
                .thenReturn(List.of());

        // Query for customerA → gets their credit
        CustomerCreditsResponse respA = creditService.getCustomerCredits(customerA);
        assertEquals(1, respA.getCredits().size());
        assertEquals(100_000L, respA.getTotalRemainingVnd());

        // Query for customerB → empty
        CustomerCreditsResponse respB = creditService.getCustomerCredits(customerB);
        assertTrue(respB.getCredits().isEmpty());
        assertEquals(0L, respB.getTotalRemainingVnd());

        // Verify repo was called with correct customer IDs
        verify(creditRepo).findByCustomerIdOrderByCreatedAtAsc(eq(customerA));
        verify(creditRepo).findByCustomerIdOrderByCreatedAtAsc(eq(customerB));
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

        when(creditRepo.findByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(List.of(c1, c2));
        when(appRepo.findByCreditIdOrderByCreatedAtAsc(credit1Id))
                .thenReturn(List.of(app1));
        when(appRepo.findByCreditIdOrderByCreatedAtAsc(credit2Id))
                .thenReturn(List.of(app2));
        when(invoiceRepo.findById(sharedInvoiceId))
                .thenReturn(Optional.of(sharedInv));

        creditService.getCustomerCredits(customerId);

        // Invoice looked up only once despite two applications referencing it
        verify(invoiceRepo, times(1)).findById(eq(sharedInvoiceId));
    }
}
