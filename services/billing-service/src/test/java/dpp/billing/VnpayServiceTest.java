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

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VnpayService covering createPaymentUrl, processReturn, and
 * queryStatus (task 21.6, R33.2/R33.3). IPN idempotency is covered separately
 * in {@link VnpayIpnPropertyTest}.
 */
class VnpayServiceTest {

    private static final String SECRET = "test-service-secret";
    private static final String TMN_CODE = "TEST001";

    private VnpayConfig config() {
        VnpayConfig c = new VnpayConfig();
        c.setHashSecret(SECRET);
        c.setTmnCode(TMN_CODE);
        c.setReturnUrl("http://localhost:3001/payment-result");
        c.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        return c;
    }

    private VnpayConfig emptyConfig() {
        VnpayConfig c = new VnpayConfig();
        c.setHashSecret("");
        c.setTmnCode("");
        return c;
    }

    private VnpayService serviceWith(VnpayConfig cfg, VnpayPaymentRepository payRepo,
                                     InvoiceRepository invRepo, BillingService billing) {
        return new VnpayService(cfg, invRepo, payRepo, billing);
    }

    private Invoice unpaidInvoice(UUID invoiceId, long amountVnd) {
        Invoice inv = new Invoice();
        inv.setInvoiceId(invoiceId);
        inv.setOrderId(UUID.randomUUID());
        inv.setAmountVnd(amountVnd);
        inv.setStatus(InvoiceStatus.unpaid);
        inv.setCreatedAt(OffsetDateTime.now());
        return inv;
    }

    private Invoice paidInvoice(UUID invoiceId, long amountVnd) {
        Invoice inv = unpaidInvoice(invoiceId, amountVnd);
        inv.setStatus(InvoiceStatus.paid);
        return inv;
    }

    private VnpayPayment pendingPayment(String txnRef, UUID invoiceId, long amountVnd) {
        VnpayPayment p = new VnpayPayment();
        p.setPaymentId(UUID.randomUUID());
        p.setInvoiceId(invoiceId);
        p.setVnpTxnRef(txnRef);
        p.setAmountVnd(amountVnd);
        p.setStatus("pending");
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    // ==================== createPaymentUrl ====================

    @Test
    void createPaymentUrlReturnsUrlAndTxnRef() {
        UUID invoiceId = UUID.randomUUID();
        long amount = 2_000_000L;

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, amount)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> result = svc.createPaymentUrl(invoiceId, "192.168.1.1");

        assertNotNull(result.get("payment_url"));
        assertTrue(result.get("payment_url").startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        assertNotNull(result.get("vnp_txn_ref"));
        assertTrue(result.get("vnp_txn_ref").startsWith(invoiceId.toString()));
        verify(payRepo, times(1)).save(any());
    }

    @Test
    void createPaymentUrlAmountIsMultipliedBy100() {
        UUID invoiceId = UUID.randomUUID();
        long amount = 1_500_000L;

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, amount)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> result = svc.createPaymentUrl(invoiceId, "127.0.0.1");

        String url = result.get("payment_url");
        assertTrue(url.contains("vnp_Amount=150000000"), "amount must be x100 in VNPAY URL");
    }

    @Test
    void createPaymentUrlThrowsWhenInvoiceNotFound() {
        UUID invoiceId = UUID.randomUUID();

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.empty());

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlThrowsWhenInvoiceAlreadyPaid() {
        UUID invoiceId = UUID.randomUUID();

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(paidInvoice(invoiceId, 1_000_000L)));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlThrows503WhenCredentialsNotConfigured() {
        UUID invoiceId = UUID.randomUUID();

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, 1_000_000L)));

        VnpayService svc = serviceWith(emptyConfig(), payRepo, invRepo, billing);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createPaymentUrl(invoiceId, "127.0.0.1"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    void createPaymentUrlSavesPendingPaymentRecord() {
        UUID invoiceId = UUID.randomUUID();
        long amount = 3_000_000L;

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, amount)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        svc.createPaymentUrl(invoiceId, "10.0.0.1");

        verify(payRepo, times(1)).save(argThat(p -> {
            VnpayPayment vp = (VnpayPayment) p;
            return "pending".equals(vp.getStatus())
                    && vp.getInvoiceId().equals(invoiceId)
                    && vp.getAmountVnd() == amount
                    && vp.getVnpTxnRef() != null;
        }));
    }

    @Test
    void createPaymentUrlUsesClientIpOrDefault() {
        UUID invoiceId = UUID.randomUUID();

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, 500_000L)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);

        Map<String, String> result = svc.createPaymentUrl(invoiceId, null);
        assertTrue(result.get("payment_url").contains("vnp_IpAddr=127.0.0.1"),
                "null IP should default to 127.0.0.1");

        result = svc.createPaymentUrl(invoiceId, "203.0.113.5");
        assertTrue(result.get("payment_url").contains("vnp_IpAddr=203.0.113.5"),
                "should use provided client IP");
    }

    @Test
    void createPaymentUrlContainsRequiredVnpayParams() {
        UUID invoiceId = UUID.randomUUID();

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        BillingService billing = mock(BillingService.class);

        when(invRepo.findById(invoiceId)).thenReturn(Optional.of(unpaidInvoice(invoiceId, 1_000_000L)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        String url = svc.createPaymentUrl(invoiceId, "127.0.0.1").get("payment_url");

        assertTrue(url.contains("vnp_Version=2.1.0"));
        assertTrue(url.contains("vnp_Command=pay"));
        assertTrue(url.contains("vnp_TmnCode=" + TMN_CODE));
        assertTrue(url.contains("vnp_CurrCode=VND"));
        assertTrue(url.contains("vnp_Locale=vn"));
        assertTrue(url.contains("vnp_ReturnUrl="));
        assertTrue(url.contains("vnp_SecureHash="));
        assertTrue(url.contains("vnp_CreateDate="));
        assertTrue(url.contains("vnp_ExpireDate="));
    }

    // ==================== processReturn ====================

    private Map<String, String> returnParams(String txnRef, String responseCode, boolean validSignature) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", TMN_CODE);
        params.put("vnp_Amount", "100000000");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionNo", "14009999");
        params.put("vnp_BankCode", "NCB");

        if (validSignature) {
            String qs = VnpaySigner.buildQueryString(params);
            params.put("vnp_SecureHash", VnpaySigner.sign(qs, SECRET));
        } else {
            params.put("vnp_SecureHash", "invalidhash123");
        }
        return params;
    }

    @Test
    void processReturnSuccessReturnsSuccessStatus() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> params = returnParams("TXN-OK-001", "00", true);

        Map<String, String> result = svc.processReturn(params);

        assertEquals("success", result.get("status"));
        assertEquals("00", result.get("vnp_response_code"));
        assertEquals("TXN-OK-001", result.get("vnp_txn_ref"));
    }

    @Test
    void processReturnFailedResponseReturnsFailedStatus() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> params = returnParams("TXN-FAIL-001", "24", true);

        Map<String, String> result = svc.processReturn(params);

        assertEquals("failed", result.get("status"));
        assertEquals("24", result.get("vnp_response_code"));
    }

    @Test
    void processReturnInvalidSignatureReturnsInvalidStatus() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> params = returnParams("TXN-BAD-SIG", "00", false);

        Map<String, String> result = svc.processReturn(params);

        assertEquals("invalid", result.get("status"));
    }

    @Test
    void processReturnDoesNotCallPayInvoice() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> params = returnParams("TXN-RETURN-001", "00", true);

        svc.processReturn(params);

        verify(billing, never()).payInvoice(any());
        verify(payRepo, never()).save(any());
    }

    @Test
    void processReturnWithNullResponseCodeReturnsFailed() {
        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", "TXN-NULL-RC");
        String qs = VnpaySigner.buildQueryString(params);
        params.put("vnp_SecureHash", VnpaySigner.sign(qs, SECRET));

        Map<String, String> result = svc.processReturn(params);

        assertEquals("failed", result.get("status"));
        assertEquals("", result.get("vnp_response_code"));
    }

    // ==================== queryStatus ====================

    @Test
    void queryStatusReturnsPaymentDetails() {
        String txnRef = "TXN-STATUS-001";
        UUID invoiceId = UUID.randomUUID();

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayPayment payment = pendingPayment(txnRef, invoiceId, 1_000_000L);
        payment.setStatus("success");
        payment.setVnpTransactionNo("14008888");
        payment.setVnpResponseCode("00");
        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(payment));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> result = svc.queryStatus(txnRef);

        assertEquals(txnRef, result.get("vnp_txn_ref"));
        assertEquals("success", result.get("status"));
        assertEquals("1000000", result.get("amount_vnd"));
        assertEquals("00", result.get("vnp_response_code"));
        assertEquals("14008888", result.get("vnp_transaction_no"));
    }

    @Test
    void queryStatusThrowsWhenNotFound() {
        String txnRef = "NONEXISTENT-TXN";

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.empty());

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.queryStatus(txnRef));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void queryStatusReturnsEmptyStringsForNullFields() {
        String txnRef = "TXN-PENDING-001";
        UUID invoiceId = UUID.randomUUID();

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayPayment payment = pendingPayment(txnRef, invoiceId, 500_000L);
        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(payment));

        VnpayService svc = serviceWith(config(), payRepo, invRepo, billing);
        Map<String, String> result = svc.queryStatus(txnRef);

        assertEquals("pending", result.get("status"));
        assertEquals("", result.get("vnp_response_code"));
        assertEquals("", result.get("vnp_transaction_no"));
    }

    // ==================== VnpayConfig defaults ====================

    @Test
    void vnpayConfigDefaultsToSandboxUrls() {
        VnpayConfig cfg = new VnpayConfig();
        assertEquals("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", cfg.getPayUrl());
        assertEquals("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction", cfg.getApiUrl());
        assertEquals("2.1.0", cfg.getVersion());
        assertEquals("vn", cfg.getLocale());
        assertEquals("pay", cfg.getCommand());
        assertEquals("VND", cfg.getCurrCode());
        assertEquals("", cfg.getTmnCode());
        assertEquals("", cfg.getHashSecret());
    }
}
