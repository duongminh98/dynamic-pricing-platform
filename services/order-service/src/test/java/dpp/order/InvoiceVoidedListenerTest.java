package dpp.order;

import dpp.order.consumer.InvoiceVoidedListener;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvoiceVoidedListenerTest {

    @Test
    void endorsementInvoiceVoidedTerminatesEndorsement() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        InvoiceVoidedListener listener = new InvoiceVoidedListener(lifecycle);

        UUID endorsementId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String message = "{\"invoice_id\":\"" + invoiceId + "\",\"order_id\":\"" + UUID.randomUUID()
                + "\",\"endorsement_request_id\":\"" + endorsementId + "\"}";

        listener.onInvoiceVoided(message, UUID.randomUUID().toString());

        verify(lifecycle, times(1)).voidEndorsementForVoidedInvoice(endorsementId, invoiceId);
    }

    @Test
    void orderInvoiceVoidedIsIgnored() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        InvoiceVoidedListener listener = new InvoiceVoidedListener(lifecycle);

        String message = "{\"invoice_id\":\"" + UUID.randomUUID() + "\",\"order_id\":\"" + UUID.randomUUID() + "\"}";

        listener.onInvoiceVoided(message, null);

        verify(lifecycle, never()).voidEndorsementForVoidedInvoice(any(), any());
    }

    @Test
    void explicitNullEndorsementIdIsIgnored() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        InvoiceVoidedListener listener = new InvoiceVoidedListener(lifecycle);

        String message = "{\"invoice_id\":\"" + UUID.randomUUID() + "\",\"endorsement_request_id\":null}";

        listener.onInvoiceVoided(message, null);

        verify(lifecycle, never()).voidEndorsementForVoidedInvoice(any(), any());
    }

    @Test
    void badMessageThrows() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        InvoiceVoidedListener listener = new InvoiceVoidedListener(lifecycle);

        assertThrows(RuntimeException.class, () -> listener.onInvoiceVoided("not-json", null));
        verify(lifecycle, never()).voidEndorsementForVoidedInvoice(any(), any());
    }
}
