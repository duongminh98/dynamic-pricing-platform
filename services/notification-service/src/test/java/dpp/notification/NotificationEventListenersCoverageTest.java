package dpp.notification;

import dpp.notification.consumer.NotificationEventListeners;
import dpp.notification.entity.CustomerEmailProjection;
import dpp.notification.repository.CustomerEmailProjectionRepository;
import dpp.notification.service.NotificationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Broad coverage for every event listener in {@link NotificationEventListeners}.
 * Each listener parses a JSON payload, builds a human-readable message, and
 * delegates to a mocked NotificationService. We assert the type routing and a
 * few salient content fragments per event so the message builders are exercised
 * end-to-end without a broker or DB.
 */
@Tag("Feature: dynamic-pricing-platform")
class NotificationEventListenersCoverageTest {

    private final CustomerEmailProjectionRepository emailRepo = mock(CustomerEmailProjectionRepository.class);

    // handle() parses policy_id / customer_id as UUIDs, so those fields must be
    // real UUID strings; all other id fields (claim_id, endorsement_request_id…)
    // are free text.
    private static final String PID = UUID.randomUUID().toString();

    private NotificationEventListeners listeners(NotificationService svc) {
        return new NotificationEventListeners(svc, emailRepo);
    }

    private String captureMessage(NotificationService svc, String type) {
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(svc).createNotification(any(), any(), any(), eq(type), msg.capture());
        return msg.getValue();
    }

    @Test
    void policyIssuedBuildsMessage() {
        NotificationService svc = mock(NotificationService.class);
        String msg = """
            {"policy_id":"%s","customer_id":"%s","product_id":"HEALTH_BASIC",
             "final_premium_vnd":"298000","term_days":"365"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID());
        listeners(svc).onPolicyIssued(msg, "e1");
        String m = captureMessage(svc, "PolicyIssued");
        assertTrue(m.contains("issued"));
        assertTrue(m.contains("298,000"));
        assertTrue(m.contains("365"));
    }

    @Test
    void claimChangedApprovedRejectedAndSanction() {
        // approved
        NotificationService svc1 = mock(NotificationService.class);
        listeners(svc1).onClaimChanged("""
            {"claim_id":"c1","policy_id":"11111111-1111-1111-1111-111111111111","status":"approved","paid_amount_vnd":"5000000","admin_note":"ok"}
            """, "e-app");
        assertTrue(captureMessage(svc1, "ClaimStatusChanged").contains("approved"));

        // rejected
        NotificationService svc2 = mock(NotificationService.class);
        listeners(svc2).onClaimChanged("""
            {"claim_id":"c2","policy_id":"11111111-1111-1111-1111-111111111111","status":"rejected","admin_note":"fraud"}
            """, "e-rej");
        assertTrue(captureMessage(svc2, "ClaimStatusChanged").contains("rejected"));

        // misrepresentation sanction
        NotificationService svc3 = mock(NotificationService.class);
        listeners(svc3).onClaimChanged("""
            {"claim_id":"c3","policy_id":"11111111-1111-1111-1111-111111111111","misrepresentation_sanction":"PROPORTIONAL_REDUCTION",
             "paid_amount_vnd":"1000000","admin_note":"undisclosed"}
            """, "e-san");
        assertTrue(captureMessage(svc3, "ClaimStatusChanged").contains("sanction"));

        // generic status
        NotificationService svc4 = mock(NotificationService.class);
        listeners(svc4).onClaimChanged("""
            {"claim_id":"c4","status":"under_review","paid_amount_vnd":"0"}
            """, "e-gen");
        assertTrue(captureMessage(svc4, "ClaimStatusChanged").contains("status"));
    }

    @Test
    void claimSubmittedBuildsMessage() {
        NotificationService svc = mock(NotificationService.class);
        listeners(svc).onClaimSubmitted("""
            {"claim_id":"c9","policy_id":"11111111-1111-1111-1111-111111111111","loss_type":"collision","estimated_cost":"3000000"}
            """, "e2");
        String m = captureMessage(svc, "ClaimSubmitted");
        assertTrue(m.contains("submitted"));
        assertTrue(m.contains("collision"));
    }

    @Test
    void endorsementAppliedSubmittedCancelledRejected() {
        NotificationService a = mock(NotificationService.class);
        listeners(a).onEndorsement("""
            {"endorsement_request_id":"en1","policy_id":"11111111-1111-1111-1111-111111111111","effective_date":"2026-07-01",
             "premium_old":"100000","premium_new":"150000"}
            """, "ea");
        assertTrue(captureMessage(a, "EndorsementApplied").contains("applied"));

        NotificationService s = mock(NotificationService.class);
        listeners(s).onEndorsementSubmitted("""
            {"endorsement_request_id":"en2","policy_id":"11111111-1111-1111-1111-111111111111","effective_date":"2026-07-01",
             "difference_vnd":"50000","pro_rated_charge_vnd":"25000"}
            """, "es");
        assertTrue(captureMessage(s, "EndorsementSubmitted").contains("submitted"));

        NotificationService c = mock(NotificationService.class);
        listeners(c).onEndorsementCancelled("""
            {"endorsement_request_id":"en3","policy_id":"11111111-1111-1111-1111-111111111111"}
            """, "ec");
        assertTrue(captureMessage(c, "EndorsementCancelled").contains("cancelled"));

        NotificationService r = mock(NotificationService.class);
        listeners(r).onEndorsementRejected("""
            {"endorsement_request_id":"en4","policy_id":"11111111-1111-1111-1111-111111111111","review_reason":"invalid"}
            """, "er");
        assertTrue(captureMessage(r, "EndorsementRejected").contains("rejected"));
    }

    @Test
    void policyRenewedNeedsPaymentAndActivated() {
        NotificationService pay = mock(NotificationService.class);
        listeners(pay).onRenewed("""
            {"policy_id":"22222222-2222-2222-2222-222222222222","previous_policy_id":"prev-1","renewal_number":"1",
             "renewed_premium_vnd":"200000","credit_applied_vnd":"50000","net_due_vnd":"150000",
             "new_effective_date":"2026-08-01","new_expiration_date":"2027-08-01","payment_required":"true"}
            """, "rn1");
        assertTrue(captureMessage(pay, "PolicyRenewed").contains("pay"));

        NotificationService act = mock(NotificationService.class);
        listeners(act).onRenewed("""
            {"policy_id":"22222222-2222-2222-2222-222222222222","renewed_premium_vnd":"200000",
             "new_effective_date":"2026-08-01","new_expiration_date":"2027-08-01","payment_required":"false"}
            """, "rn2");
        assertTrue(captureMessage(act, "PolicyRenewed").contains("renewed"));
    }

    @Test
    void policyCancelledBuildsMessage() {
        NotificationService svc = mock(NotificationService.class);
        listeners(svc).onCancelled("""
            {"policy_id":"33333333-3333-3333-3333-333333333333","cancel_date":"2026-07-01","remaining_days":"200","term_days":"365",
             "refundable_credit_vnd":"120000"}
            """, "cn");
        String m = captureMessage(svc, "PolicyCancelled");
        assertTrue(m.contains("cancelled"));
        assertTrue(m.contains("120,000"));
    }

    @Test
    void endorsementPendingPaymentAndOverdueAndCredit() {
        NotificationService pp = mock(NotificationService.class);
        listeners(pp).onEndorsementPendingPayment("""
            {"endorsement_request_id":"en5","policy_id":"11111111-1111-1111-1111-111111111111","invoice_id":"inv1",
             "additional_charge_vnd":"75000","due_date":"2026-07-10"}
            """, "pp");
        assertTrue(captureMessage(pp, "EndorsementPendingPayment").contains("pay"));

        NotificationService ov = mock(NotificationService.class);
        listeners(ov).onEndorsementOverdue("""
            {"endorsement_request_id":"en6","policy_id":"11111111-1111-1111-1111-111111111111","additional_charge_vnd":"75000",
             "due_date":"2026-07-10"}
            """, "ov");
        assertTrue(captureMessage(ov, "EndorsementOverdue").contains("expired"));

        NotificationService cr = mock(NotificationService.class);
        listeners(cr).onCreditIssued("""
            {"policy_id":"11111111-1111-1111-1111-111111111111","endorsement_request_id":"en7","amount_vnd":"30000"}
            """, "cr");
        assertTrue(captureMessage(cr, "EndorsementCreditIssued").contains("credit"));
    }

    @Test
    void refundRequestedCompletedRejected() {
        NotificationService rq = mock(NotificationService.class);
        listeners(rq).onRefundRequested("""
            {"policy_id":"11111111-1111-1111-1111-111111111111","refund_id":"rf1","amount_vnd":"90000"}
            """, "rq");
        assertTrue(captureMessage(rq, "RefundRequested").contains("refund"));

        NotificationService cp = mock(NotificationService.class);
        listeners(cp).onRefundCompleted("""
            {"policy_id":"11111111-1111-1111-1111-111111111111","refund_id":"rf1","amount_vnd":"90000","payment_reference":"pay-xyz"}
            """, "cp");
        assertTrue(captureMessage(cp, "RefundCompleted").contains("completed"));

        NotificationService rj = mock(NotificationService.class);
        listeners(rj).onRefundRejected("""
            {"policy_id":"11111111-1111-1111-1111-111111111111","refund_id":"rf1","amount_vnd":"90000","note":"not eligible"}
            """, "rj");
        assertTrue(captureMessage(rj, "RefundRejected").contains("rejected"));
    }

    @Test
    void invoiceVoidedBuildsMessage() {
        NotificationService svc = mock(NotificationService.class);
        listeners(svc).onInvoiceVoided("""
            {"invoice_id":"inv9","order_id":"ord9","amount_vnd":"298000"}
            """, "iv");
        String m = captureMessage(svc, "InvoiceVoided");
        assertTrue(m.contains("voided"));
        assertTrue(m.contains("298,000"));
    }

    @Test
    void handleWrapsParseErrorsInRuntimeException() {
        NotificationService svc = mock(NotificationService.class);
        assertThrows(RuntimeException.class,
                () -> listeners(svc).onPolicyIssued("not-json", "bad"));
        verify(svc, never()).createNotification(any(), any(), any(), any(), any());
    }

    // ── customer email projection ──

    @Test
    void customerCreatedUpsertsEmailProjection() {
        NotificationService svc = mock(NotificationService.class);
        UUID customerId = UUID.randomUUID();
        when(emailRepo.findById(customerId)).thenReturn(Optional.empty());

        listeners(svc).onCustomerCreated("""
            {"customer_id":"%s","email":"a@b.com","updated_at":"2026-07-01T00:00:00+00:00"}
            """.formatted(customerId));

        ArgumentCaptor<CustomerEmailProjection> captor = ArgumentCaptor.forClass(CustomerEmailProjection.class);
        verify(emailRepo).save(captor.capture());
        assertEquals("a@b.com", captor.getValue().getEmail());
        assertEquals(customerId, captor.getValue().getCustomerId());
    }

    @Test
    void customerEmailUpdatedSkipsWhenEmailMissing() {
        NotificationService svc = mock(NotificationService.class);
        reset(emailRepo);
        listeners(svc).onCustomerEmailUpdated("""
            {"customer_id":"%s"}
            """.formatted(UUID.randomUUID()));
        verify(emailRepo, never()).save(any());
    }

    @Test
    void customerProfileUpdatedUpsertsWithFallbackTime() {
        NotificationService svc = mock(NotificationService.class);
        reset(emailRepo);
        UUID customerId = UUID.randomUUID();
        when(emailRepo.findById(customerId)).thenReturn(Optional.empty());
        listeners(svc).onCustomerProfileUpdated("""
            {"customer_id":"%s","email":"c@d.com","updated_at":"garbage"}
            """.formatted(customerId));
        ArgumentCaptor<CustomerEmailProjection> captor = ArgumentCaptor.forClass(CustomerEmailProjection.class);
        verify(emailRepo).save(captor.capture());
        assertNotNull(captor.getValue().getUpdatedAt());
    }
}
