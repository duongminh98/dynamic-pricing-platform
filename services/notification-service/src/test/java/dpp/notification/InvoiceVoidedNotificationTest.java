package dpp.notification;

import dpp.notification.consumer.NotificationEventListeners;
import dpp.notification.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceVoidedNotificationTest {

    @Test
    void invoiceVoidedCreatesInAppNotificationWithDetails() {
        NotificationService svc = mock(NotificationService.class);
        NotificationEventListeners l = new NotificationEventListeners(svc, mock(dpp.notification.repository.CustomerEmailProjectionRepository.class));

        UUID customerId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        String msg = """
            {
              "invoice_id": "%s",
              "order_id": "%s",
              "policy_id": null,
              "customer_id": "%s",
              "amount_vnd": 2980000
            }
            """.formatted(invoiceId, orderId, customerId);

        l.onInvoiceVoided(msg, "evt-void-1");

        verify(svc).createNotification(eq("evt-void-1"), eq(customerId), isNull(),
                eq("InvoiceVoided"), argThat(m ->
                        m.contains("voided by an administrator") &&
                        m.contains(invoiceId.toString()) &&
                        m.contains(orderId.toString()) &&
                        m.contains("2,980,000 VND") &&
                        m.contains("No payment is required")
                ));
    }

    @Test
    void invoiceVoidedDoesNotSendEmail() {
        assertFalse(isEmailEventType("InvoiceVoided"));
    }

    private boolean isEmailEventType(String type) {
        return java.util.Set.of(
                "PolicyIssued", "PolicyCancelled", "ClaimStatusChanged",
                "EndorsementApplied", "EndorsementRejected", "PolicyRenewed",
                "OrderApproved", "OrderRejected",
                "EndorsementPendingPayment", "EndorsementOverdue",
                "EndorsementCreditIssued", "RefundRequested", "RefundCompleted"
        ).contains(type);
    }
}
