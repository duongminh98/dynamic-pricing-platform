package dpp.order;

import dpp.order.consumer.InvoicePaidListener;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyIssuanceService;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoicePaidListenerTest {

    private InvoicePaidListener newListener(PolicyIssuanceService issuanceService,
                                            PolicyLifecycleService lifecycleService,
                                            PolicyRepository policyRepository) {
        return new InvoicePaidListener(issuanceService, lifecycleService, policyRepository);
    }

    @Test
    void onInvoicePaidWithPolicyIdIssuesPolicy() {
        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.findById(any())).thenReturn(Optional.empty());
        InvoicePaidListener listener = newListener(issuanceService, mock(PolicyLifecycleService.class), policyRepo);

        UUID orderId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String message = "{\"order_id\":\"" + orderId + "\",\"policy_id\":\"" + policyId + "\"}";
        String eventId = UUID.randomUUID().toString();

        listener.onInvoicePaid(message, eventId);

        verify(issuanceService, times(1)).issuePolicy(eventId, orderId, policyId);
    }

    @Test
    void onInvoicePaidWithNullPolicyIdIssuesPolicy() {
        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        InvoicePaidListener listener = newListener(issuanceService, mock(PolicyLifecycleService.class), policyRepo);

        UUID orderId = UUID.randomUUID();
        String message = "{\"order_id\":\"" + orderId + "\"}";
        String eventId = UUID.randomUUID().toString();

        listener.onInvoicePaid(message, eventId);

        verify(issuanceService, times(1)).issuePolicy(eventId, orderId, null);
    }

    @Test
    void onInvoicePaidWithExplicitNullPolicyIdIssuesPolicy() {
        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        InvoicePaidListener listener = newListener(issuanceService, mock(PolicyLifecycleService.class), policyRepo);

        UUID orderId = UUID.randomUUID();
        String message = "{\"order_id\":\"" + orderId + "\",\"policy_id\":null}";
        String eventId = UUID.randomUUID().toString();

        listener.onInvoicePaid(message, eventId);

        verify(issuanceService, times(1)).issuePolicy(eventId, orderId, null);
    }

    @Test
    void onInvoicePaidThrowsOnBadMessage() {
        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        InvoicePaidListener listener = newListener(issuanceService, mock(PolicyLifecycleService.class), policyRepo);

        assertThrows(RuntimeException.class, () -> listener.onInvoicePaid("not-json", null));
        verify(issuanceService, never()).issuePolicy(any(), any(), any());
    }

    @Test
    void onInvoicePaidThrowsOnMissingOrderId() {
        PolicyIssuanceService issuanceService = mock(PolicyIssuanceService.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        InvoicePaidListener listener = newListener(issuanceService, mock(PolicyLifecycleService.class), policyRepo);

        assertThrows(RuntimeException.class, () -> listener.onInvoicePaid("{}", null));
        verify(issuanceService, never()).issuePolicy(any(), any(), any());
    }
}
