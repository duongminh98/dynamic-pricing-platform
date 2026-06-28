package dpp.billing;

import dpp.billing.controller.BillingController;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.VnpayService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the direct-pay.enabled flag (spec: VNPAY as primary payment path).
 * When disabled (default): POST /billing/invoices/{id}/pay returns 403.
 * When enabled: proceeds to BillingService.
 */
class DirectPayFlagTest {

    private Jwt mockJwt() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(UUID.randomUUID().toString());
        return jwt;
    }

    @Test
    void directPayDisabledReturns403() {
        BillingService billingService = mock(BillingService.class);
        VnpayService vnpayService = mock(VnpayService.class);
        BillingController controller = new BillingController(billingService, mock(CreditService.class), vnpayService, false);

        UUID invoiceId = UUID.randomUUID();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.payInvoice(mockJwt(), invoiceId));
        assertEquals(ErrorCode.FORBIDDEN_RESOURCE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("VNPAY"));
        verify(billingService, never()).payInvoiceAsCustomer(any(), any());
    }

    @Test
    void directPayEnabledProceedsToService() {
        BillingService billingService = mock(BillingService.class);
        VnpayService vnpayService = mock(VnpayService.class);
        BillingController controller = new BillingController(billingService, mock(CreditService.class), vnpayService, true);

        UUID invoiceId = UUID.randomUUID();
        Jwt jwt = mockJwt();
        controller.payInvoice(jwt, invoiceId);

        verify(billingService, times(1)).payInvoiceAsCustomer(eq(invoiceId), any());
    }
}
