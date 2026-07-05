package dpp.billing;

import dpp.billing.config.VnpayConfig;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.entity.VnpayPayment;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.repository.VnpayPaymentRepository;
import dpp.billing.service.BillingService;
import dpp.billing.service.VnpayService;
import dpp.billing.service.VnpaySigner;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnpayServiceExtraTest {

    private static final String SECRET = "test-extra-secret";

    private VnpayConfig config() {
        VnpayConfig c = new VnpayConfig();
        c.setHashSecret(SECRET);
        c.setTmnCode("TMN001");
        c.setReturnUrl("http://localhost:3001/payment-result");
        c.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        return c;
    }

    private VnpayService serviceWith(VnpayPaymentRepository payRepo, InvoiceRepository invRepo, BillingService billing) {
        return new VnpayService(config(), invRepo, payRepo, billing);
    }

    @Test
    void createPaymentUrlRejectsUnknownInvoice() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        when(invRepo.findById(invoiceId)).thenReturn(Optional.empty());

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlRejectsNonUnpaidInvoice() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.paid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.INVOICE_NOT_PAYABLE, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlRejectsWhenCredentialsNotConfigured() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));

        VnpayConfig emptyConfig = new VnpayConfig();
        VnpayService svc = new VnpayService(emptyConfig, invRepo, payRepo, mock(BillingService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlRejectsPlaceholderCredentials() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));

        VnpayConfig placeholderConfig = new VnpayConfig();
        placeholderConfig.setTmnCode("SANDBOXPLACEHOLDER");
        placeholderConfig.setHashSecret("SANDBOXHASHSECRETPLACEHOLDER");
        VnpayService svc = new VnpayService(placeholderConfig, invRepo, payRepo, mock(BillingService.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        verify(payRepo, never()).save(any());
    }

    @Test
    void createPaymentUrlSucceeds() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(1_000_000L);
        inv.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));
        when(payRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.createPaymentUrl(invoiceId, "127.0.0.1");

        assertNotNull(result.get("payment_url"));
        assertNotNull(result.get("vnp_txn_ref"));
        assertTrue(result.get("payment_url").startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        verify(payRepo, times(1)).save(any());
    }

    @Test
    void createPaymentUrlWithNullIpUsesDefault() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(500_000L);
        inv.setStatus(InvoiceStatus.unpaid);
        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(inv));
        when(payRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.createPaymentUrl(invoiceId, null);

        assertNotNull(result.get("payment_url"));
    }

    @Test
    void processReturnReturnsSuccessForValidResponse00() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN001");
        params.put("vnp_TxnRef", "TXN-001");
        params.put("vnp_ResponseCode", "00");
        String qs = VnpaySigner.buildQueryString(params);
        params.put("vnp_SecureHash", VnpaySigner.sign(qs, SECRET));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.processReturn(params);

        assertEquals("success", result.get("status"));
        assertEquals("TXN-001", result.get("vnp_txn_ref"));
    }

    @Test
    void processReturnReturnsFailedForNon00Response() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN001");
        params.put("vnp_TxnRef", "TXN-002");
        params.put("vnp_ResponseCode", "24");
        String qs = VnpaySigner.buildQueryString(params);
        params.put("vnp_SecureHash", VnpaySigner.sign(qs, SECRET));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.processReturn(params);

        assertEquals("failed", result.get("status"));
    }

    @Test
    void processReturnReturnsInvalidForBadSignature() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN001");
        params.put("vnp_TxnRef", "TXN-003");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_SecureHash", "deadbeef");

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.processReturn(params);

        assertEquals("invalid", result.get("status"));
    }

    @Test
    void queryStatusReturnsPaymentInfo() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);

        VnpayPayment payment = new VnpayPayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setInvoiceId(UUID.randomUUID());
        payment.setVnpTxnRef("TXN-QUERY-1");
        payment.setAmountVnd(1_500_000L);
        payment.setStatus("success");
        payment.setVnpResponseCode("00");
        payment.setVnpTransactionNo("14009999");
        when(payRepo.findByVnpTxnRef("TXN-QUERY-1")).thenReturn(Optional.of(payment));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.queryStatus("TXN-QUERY-1");

        assertEquals("success", result.get("status"));
        assertEquals("1500000", result.get("amount_vnd"));
        assertEquals("00", result.get("vnp_response_code"));
        assertEquals("14009999", result.get("vnp_transaction_no"));
    }

    @Test
    void queryStatusRejectsUnknownTxnRef() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        when(payRepo.findByVnpTxnRef("UNKNOWN")).thenReturn(Optional.empty());

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.queryStatus("UNKNOWN"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void queryStatusHandlesNullFields() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);

        VnpayPayment payment = new VnpayPayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setInvoiceId(UUID.randomUUID());
        payment.setVnpTxnRef("TXN-QUERY-2");
        payment.setAmountVnd(500_000L);
        payment.setStatus("pending");
        payment.setVnpResponseCode(null);
        payment.setVnpTransactionNo(null);
        when(payRepo.findByVnpTxnRef("TXN-QUERY-2")).thenReturn(Optional.of(payment));

        VnpayService svc = serviceWith(payRepo, invRepo, mock(BillingService.class));
        Map<String, String> result = svc.queryStatus("TXN-QUERY-2");

        assertEquals("pending", result.get("status"));
        assertEquals("", result.get("vnp_response_code"));
        assertEquals("", result.get("vnp_transaction_no"));
    }
}
