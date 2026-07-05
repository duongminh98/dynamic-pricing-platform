package dpp.billing.service;

import dpp.billing.config.VnpayConfig;
import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import dpp.billing.entity.VnpayPayment;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.repository.VnpayPaymentRepository;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
/**
 * VNPAY payment integration service (task 21.2-21.4, R33.2).
 *
 * <p>Creates payment URLs, processes return/IPN callbacks, and queries
 * transaction status. The InvoicePaid event contract is unchanged -- VNPAY
 * only replaces the "confirm payment" step of the existing billing flow.</p>
 */
@Service
public class VnpayService {

    private static final DateTimeFormatter VNP_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VnpayConfig vnpayConfig;
    private final InvoiceRepository invoiceRepository;
    private final VnpayPaymentRepository vnpayPaymentRepository;
    private final BillingService billingService;

    public VnpayService(VnpayConfig vnpayConfig, InvoiceRepository invoiceRepository,
                        VnpayPaymentRepository vnpayPaymentRepository, BillingService billingService) {
        this.vnpayConfig = vnpayConfig;
        this.invoiceRepository = invoiceRepository;
        this.vnpayPaymentRepository = vnpayPaymentRepository;
        this.billingService = billingService;
    }

    /**
     * Create a VNPAY payment URL for an invoice (task 21.2). The invoice must be
     * unpaid. Returns the full payment URL + vnp_txn_ref. Creates a
     * vnpay_payment row with status=pending.
     */
    @Transactional
    public Map<String, String> createPaymentUrl(UUID invoiceId, String clientIp) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found", null));

        if (invoice.getStatus() != InvoiceStatus.unpaid) {
            throw new ServiceException(ErrorCode.INVOICE_NOT_PAYABLE,
                    "Invoice is not in a payable state", Map.of("status", invoice.getStatus().name()));
        }

        // Fail gracefully (503) instead of redirecting customers to VNPAY with
        // missing or provisioning-placeholder merchant credentials.
        if (isInvalidCredential(vnpayConfig.getTmnCode()) || isInvalidCredential(vnpayConfig.getHashSecret())) {
            throw new ServiceException(ErrorCode.SERVICE_UNAVAILABLE,
                    "VNPAY merchant credentials are not configured", null);
        }

        String txnRef = invoiceId.toString() + "-" + System.currentTimeMillis();
        long amountVnd = invoice.getAmountVnd();

        OffsetDateTime now = OffsetDateTime.now(VNPAY_ZONE);
        OffsetDateTime expire = now.plusMinutes(15);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", vnpayConfig.getVersion());
        params.put("vnp_Command", vnpayConfig.getCommand());
        params.put("vnp_TmnCode", vnpayConfig.getTmnCode());
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_CurrCode", vnpayConfig.getCurrCode());
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Payment for invoice " + invoiceId);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", vnpayConfig.getLocale());
        params.put("vnp_ReturnUrl", vnpayConfig.getReturnUrl());
        params.put("vnp_IpAddr", clientIp != null ? clientIp : "127.0.0.1");
        params.put("vnp_CreateDate", now.format(VNP_DATE));
        params.put("vnp_ExpireDate", expire.format(VNP_DATE));

        String queryString = VnpaySigner.buildQueryString(params);
        String secureHash = VnpaySigner.sign(queryString, vnpayConfig.getHashSecret());
        String paymentUrl = vnpayConfig.getPayUrl() + "?" + queryString + "&vnp_SecureHash=" + secureHash;

        VnpayPayment payment = new VnpayPayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setInvoiceId(invoiceId);
        payment.setVnpTxnRef(txnRef);
        payment.setAmountVnd(amountVnd);
        payment.setStatus("pending");
        payment.setCreatedAt(OffsetDateTime.now());
        payment.setUpdatedAt(OffsetDateTime.now());
        vnpayPaymentRepository.save(payment);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("payment_url", paymentUrl);
        result.put("vnp_txn_ref", txnRef);
        return result;
    }

    private static boolean isInvalidCredential(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.toUpperCase().contains("PLACEHOLDER");
    }

    /**
     * Process VNPAY IPN callback (task 21.3). This is the source of truth for
     * payment confirmation. Returns VNPAY response codes per the spec.
     *
     * <ul>
     *   <li>RspCode 00 -- confirmed (success + InvoicePaid enqueued)</li>
     *   <li>RspCode 01 -- order not found</li>
     *   <li>RspCode 02 -- already confirmed (idempotent)</li>
     *   <li>RspCode 04 -- amount mismatch</li>
     *   <li>RspCode 97 -- invalid signature</li>
     * </ul>
     */
    @Transactional
    public Map<String, String> processIpn(Map<String, String> params) {
        String secureHash = params.get("vnp_SecureHash");

        if (!VnpaySigner.verify(params, secureHash, vnpayConfig.getHashSecret())) {
            return ipnResponse("97", "Invalid signature");
        }

        String txnRef = params.get("vnp_TxnRef");
        VnpayPayment payment = vnpayPaymentRepository.findByVnpTxnRef(txnRef).orElse(null);

        if (payment == null) {
            return ipnResponse("01", "Order not found");
        }

        long ipnAmount = Long.parseLong(params.getOrDefault("vnp_Amount", "0")) / 100;
        if (ipnAmount != payment.getAmountVnd()) {
            return ipnResponse("04", "Invalid amount");
        }

        if ("success".equals(payment.getStatus())) {
            return ipnResponse("02", "Order already confirmed");
        }

        String responseCode = params.get("vnp_ResponseCode");
        String txnNo = params.get("vnp_TransactionNo");

        if ("00".equals(responseCode)) {
            payment.setStatus("success");
            payment.setVnpTransactionNo(txnNo);
            payment.setVnpResponseCode(responseCode);
            payment.setVnpBankCode(params.get("vnp_BankCode"));
            payment.setUpdatedAt(OffsetDateTime.now());
            vnpayPaymentRepository.save(payment);
            billingService.payInvoice(payment.getInvoiceId());
            return ipnResponse("00", "Confirmed");
        } else {
            payment.setStatus("failed");
            payment.setVnpResponseCode(responseCode);
            payment.setUpdatedAt(OffsetDateTime.now());
            vnpayPaymentRepository.save(payment);
            return ipnResponse("00", "Payment failed recorded");
        }
    }

    /**
     * Process VNPAY Return URL (task 21.3). In local development the VNPAY
     * sandbox cannot reach localhost IPN, so a valid successful browser return
     * is allowed to confirm the invoice if IPN has not already done so.
     */
    @Transactional
    public Map<String, String> processReturn(Map<String, String> params) {
        String responseCode = params.get("vnp_ResponseCode");
        String txnRef = params.get("vnp_TxnRef");

        boolean valid = VnpaySigner.verify(params, params.get("vnp_SecureHash"), vnpayConfig.getHashSecret());
        String status;
        if (!valid) {
            status = "invalid";
        } else if ("00".equals(responseCode)) {
            status = "success";
            confirmPaymentFromCallback(params, txnRef, responseCode);
        } else {
            status = "failed";
            recordFailedPayment(txnRef, responseCode);
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("vnp_txn_ref", txnRef);
        result.put("vnp_response_code", responseCode != null ? responseCode : "");
        return result;
    }

    private void confirmPaymentFromCallback(Map<String, String> params, String txnRef, String responseCode) {
        if (txnRef == null || txnRef.isBlank()) {
            return;
        }
        VnpayPayment payment = vnpayPaymentRepository.findByVnpTxnRef(txnRef).orElse(null);
        if (payment == null || "success".equals(payment.getStatus())) {
            return;
        }
        long callbackAmount = Long.parseLong(params.getOrDefault("vnp_Amount", "0")) / 100;
        if (callbackAmount != payment.getAmountVnd()) {
            throw new ServiceException(ErrorCode.PAYMENT_FAILED, "Invalid VNPAY amount", null);
        }
        payment.setStatus("success");
        payment.setVnpTransactionNo(params.get("vnp_TransactionNo"));
        payment.setVnpResponseCode(responseCode);
        payment.setVnpBankCode(params.get("vnp_BankCode"));
        payment.setUpdatedAt(OffsetDateTime.now());
        vnpayPaymentRepository.save(payment);
        billingService.payInvoice(payment.getInvoiceId());
    }

    private void recordFailedPayment(String txnRef, String responseCode) {
        if (txnRef == null || txnRef.isBlank()) {
            return;
        }
        VnpayPayment payment = vnpayPaymentRepository.findByVnpTxnRef(txnRef).orElse(null);
        if (payment == null || "success".equals(payment.getStatus())) {
            return;
        }
        payment.setStatus("failed");
        payment.setVnpResponseCode(responseCode);
        payment.setUpdatedAt(OffsetDateTime.now());
        vnpayPaymentRepository.save(payment);
    }

    /**
     * Query payment status by vnp_txn_ref (task 21.4). Returns the current
     * vnpay_payment status for frontend polling.
     */
    @Transactional(readOnly = true)
    public Map<String, String> queryStatus(String txnRef) {
        VnpayPayment payment = vnpayPaymentRepository.findByVnpTxnRef(txnRef)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Payment not found", null));

        Map<String, String> result = new LinkedHashMap<>();
        result.put("vnp_txn_ref", payment.getVnpTxnRef());
        result.put("status", payment.getStatus());
        result.put("amount_vnd", String.valueOf(payment.getAmountVnd()));
        result.put("vnp_response_code", payment.getVnpResponseCode() != null ? payment.getVnpResponseCode() : "");
        result.put("vnp_transaction_no", payment.getVnpTransactionNo() != null ? payment.getVnpTransactionNo() : "");
        return result;
    }

    private Map<String, String> ipnResponse(String rspCode, String message) {
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("RspCode", rspCode);
        resp.put("Message", message);
        return resp;
    }
}
