package dpp.billing;

import dpp.billing.config.VnpayConfig;
import dpp.billing.entity.VnpayPayment;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.repository.VnpayPaymentRepository;
import dpp.billing.service.BillingService;
import dpp.billing.service.VnpayService;
import dpp.billing.service.VnpaySigner;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Property tests for VNPAY IPN idempotency (task 21.6, R33.3).
 *
 * <p>Oracle: only (responseCode=00 + valid signature + amount match) leads to
 * paid + 1 InvoicePaid enqueue. Repeated IPN for the same txnRef returns RspCode
 * 02 (already confirmed) and does NOT enqueue a second InvoicePaid.</p>
 */
@Tag("Feature: dynamic-pricing-platform, Property 33")
class VnpayIpnPropertyTest {

    private static final String SECRET = "test-ipn-secret";

    private VnpayConfig config() {
        VnpayConfig c = new VnpayConfig();
        c.setHashSecret(SECRET);
        c.setTmnCode("TEST001");
        c.setReturnUrl("http://localhost:3001/payment-result");
        return c;
    }

    private VnpayService serviceWith(VnpayPaymentRepository payRepo, InvoiceRepository invRepo, BillingService billing) {
        return new VnpayService(config(), invRepo, payRepo, billing);
    }

    private Map<String, String> ipnParams(String txnRef, long amountVnd, String responseCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TEST001");
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionNo", "14001234");
        params.put("vnp_BankCode", "NCB");
        String qs = VnpaySigner.buildQueryString(params);
        params.put("vnp_SecureHash", VnpaySigner.sign(qs, SECRET));
        return params;
    }

    private VnpayPayment pendingPayment(String txnRef, long amountVnd) {
        VnpayPayment p = new VnpayPayment();
        p.setPaymentId(UUID.randomUUID());
        p.setInvoiceId(UUID.randomUUID());
        p.setVnpTxnRef(txnRef);
        p.setAmountVnd(amountVnd);
        p.setStatus("pending");
        return p;
    }

    @Property(tries = 100)
    void successfulIpnMarksPaidAndEnqueuesOnce(@ForAll int seed) {
        String txnRef = "TXN-" + seed;
        long amount = 1000000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayPayment payment = pendingPayment(txnRef, amount);
        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(payment));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, amount, "00");

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("00", resp.get("RspCode"));
        assertEquals("success", payment.getStatus());
        verify(billing, times(1)).payInvoice(payment.getInvoiceId());
    }

    @Property(tries = 100)
    void repeatedIpnIsIdempotent(@ForAll int seed) {
        String txnRef = "TXN-" + seed;
        long amount = 500000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        // Already success from a previous IPN
        VnpayPayment payment = pendingPayment(txnRef, amount);
        payment.setStatus("success");
        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(payment));

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, amount, "00");

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("02", resp.get("RspCode"), "repeated IPN must return 02");
        verify(billing, never()).payInvoice(any());
    }

    @Property(tries = 100)
    void amountMismatchReturns04(@ForAll int seed) {
        String txnRef = "TXN-" + seed;
        long paymentAmount = 1000000;
        long ipnAmount = 999999;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(pendingPayment(txnRef, paymentAmount)));

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, ipnAmount, "00");

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("04", resp.get("RspCode"));
        verify(billing, never()).payInvoice(any());
    }

    @Property(tries = 100)
    void invalidSignatureReturns97(@ForAll int seed) {
        String txnRef = "TXN-" + seed;
        long amount = 1000000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, amount, "00");
        params.put("vnp_SecureHash", "deadbeefdeadbeef");

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("97", resp.get("RspCode"));
        verify(billing, never()).payInvoice(any());
    }

    @Property(tries = 100)
    void unknownTxnRefReturns01(@ForAll int seed) {
        String txnRef = "UNKNOWN-" + seed;
        long amount = 1000000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.empty());

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, amount, "00");

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("01", resp.get("RspCode"));
        verify(billing, never()).payInvoice(any());
    }

    @Property(tries = 100)
    void failedResponseCodeDoesNotPay(@ForAll int seed) {
        String txnRef = "TXN-" + seed;
        long amount = 1000000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        VnpayPayment payment = pendingPayment(txnRef, amount);
        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(payment));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> params = ipnParams(txnRef, amount, "24"); // 24 = cancel

        Map<String, String> resp = svc.processIpn(params);

        assertEquals("failed", payment.getStatus());
        verify(billing, never()).payInvoice(any());
    }

    @Test
    void property33_sanity() {
        String txnRef = "SANITY-TXN";
        long amount = 1000000;

        VnpayPaymentRepository payRepo = mock(VnpayPaymentRepository.class);
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        BillingService billing = mock(BillingService.class);

        when(payRepo.findByVnpTxnRef(txnRef)).thenReturn(Optional.of(pendingPayment(txnRef, amount)));
        when(payRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VnpayService svc = serviceWith(payRepo, invRepo, billing);
        Map<String, String> resp = svc.processIpn(ipnParams(txnRef, amount, "00"));

        assertEquals("00", resp.get("RspCode"));
        verify(billing, times(1)).payInvoice(any());
    }
}
