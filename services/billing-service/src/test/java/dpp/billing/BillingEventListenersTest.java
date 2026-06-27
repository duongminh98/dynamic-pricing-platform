package dpp.billing;

import dpp.billing.consumer.BillingEventListeners;
import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.service.AdjustmentService;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.RefundService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingEventListenersTest {

    @Test
    void onEndorsementDelegatesToAdjustmentService() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        UUID policyId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String message = "{\"policy_id\":\"" + policyId + "\",\"order_id\":\"" + orderId + "\","
                + "\"premium_old\":1000000,\"premium_new\":1500000,"
                + "\"remaining_days\":200,\"term_days\":365}";
        String eventId = UUID.randomUUID().toString();

        listener.onEndorsement(message, eventId);

        verify(adjustmentService, times(1)).applyEndorsement(
                eventId, policyId, orderId, 1_000_000L, 1_500_000L, 200, 365);
    }

    @Test
    void onEndorsementThrowsOnBadMessage() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        assertThrows(RuntimeException.class, () -> listener.onEndorsement("not-json", null));
        verify(adjustmentService, never()).applyEndorsement(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onCancellationDelegatesToAdjustmentService() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        UUID policyId = UUID.randomUUID();
        String message = "{\"policy_id\":\"" + policyId + "\","
                + "\"final_premium_vnd\":2000000,"
                + "\"remaining_days\":100,\"term_days\":365}";
        String eventId = UUID.randomUUID().toString();

        listener.onCancellation(message, eventId);

        verify(adjustmentService, times(1)).applyCancellation(
                eventId, policyId, 2_000_000L, 100, 365);
    }

    @Test
    void onCancellationThrowsOnBadMessage() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        assertThrows(RuntimeException.class, () -> listener.onCancellation("bad", null));
        verify(adjustmentService, never()).applyCancellation(any(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onRenewalCreatesInvoice() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        UUID policyId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String message = "{\"policy_id\":\"" + policyId + "\",\"order_id\":\"" + orderId + "\","
                + "\"final_premium_vnd\":3000000}";
        String eventId = UUID.randomUUID().toString();

        InvoiceResponse mockResp = new InvoiceResponse();
        mockResp.setStatus(InvoiceStatus.unpaid);
        when(billingService.createInvoice(any(CreateInvoiceRequest.class))).thenReturn(mockResp);

        listener.onRenewal(message, eventId);

        verify(billingService, times(1)).createInvoice(argThat(req ->
                req.getOrderId().equals(orderId) &&
                req.getPolicyId().equals(policyId) &&
                req.getAmountVnd() == 3_000_000L));
    }

    @Test
    void onRenewalThrowsOnBadMessage() {
        AdjustmentService adjustmentService = mock(AdjustmentService.class);
        BillingService billingService = mock(BillingService.class);
        BillingEventListeners listener = new BillingEventListeners(adjustmentService, billingService, mock(CreditService.class), mock(RefundService.class));

        assertThrows(RuntimeException.class, () -> listener.onRenewal("bad", null));
        verify(billingService, never()).createInvoice(any());
    }
}
