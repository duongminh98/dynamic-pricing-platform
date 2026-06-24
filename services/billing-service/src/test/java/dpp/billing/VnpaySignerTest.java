package dpp.billing;

import dpp.billing.service.VnpaySigner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VnpaySigner (task 21.6, R33.2/R33.3).
 * Verifies HMAC-SHA512 signing + verification, sort/encode consistency,
 * and that tampered parameters fail verification.
 */
class VnpaySignerTest {

    private static final String SECRET = "test-secret-key-123456";

    @Test
    void signProduces64CharHexHmacSha512() {
        String qs = "vnp_Amount=10000000&vnp_Command=pay";
        String hash = VnpaySigner.sign(qs, SECRET);
        assertNotNull(hash);
        assertEquals(128, hash.length(), "HMAC-SHA512 produces 64 bytes = 128 hex chars");
        assertTrue(hash.matches("[0-9a-f]+"), "hex lowercase");
    }

    @Test
    void verifyAcceptsCorrectSignature() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TEST001");
        params.put("vnp_Amount", "10000000");
        params.put("vnp_TxnRef", "INV-123");
        params.put("vnp_ResponseCode", "00");

        String qs = VnpaySigner.buildQueryString(params);
        String hash = VnpaySigner.sign(qs, SECRET);
        params.put("vnp_SecureHash", hash);

        assertTrue(VnpaySigner.verify(params, hash, SECRET));
    }

    @Test
    void verifyRejectsTamperedAmount() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_TxnRef", "INV-123");

        String qs = VnpaySigner.buildQueryString(params);
        String hash = VnpaySigner.sign(qs, SECRET);

        // Tamper amount
        params.put("vnp_Amount", "99999999");
        assertFalse(VnpaySigner.verify(params, hash, SECRET));
    }

    @Test
    void verifyRejectsEmptyHash() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Amount", "10000000");
        assertFalse(VnpaySigner.verify(params, "", SECRET));
        assertFalse(VnpaySigner.verify(params, null, SECRET));
    }

    @Test
    void buildQueryStringSortsKeysAscending() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Zebra", "z");
        params.put("vnp_Alpha", "a");
        params.put("vnp_Mango", "m");

        String qs = VnpaySigner.buildQueryString(params);
        assertEquals("vnp_Alpha=a&vnp_Mango=m&vnp_Zebra=z", qs);
    }

    @Test
    void buildQueryStringExcludesSecureHashAndEmptyValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_SecureHash", "abc123");
        params.put("vnp_Empty", "");

        String qs = VnpaySigner.buildQueryString(params);
        assertEquals("vnp_Amount=10000000", qs);
    }

    @Test
    void buildQueryStringEncodesSpacesAsPercent20() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_OrderInfo", "Payment for invoice 123");

        String qs = VnpaySigner.buildQueryString(params);
        assertEquals("vnp_OrderInfo=Payment%20for%20invoice%20123", qs);
    }

    @Test
    void signIsDeterministicForSameInput() {
        String qs = "vnp_Amount=10000000&vnp_Command=pay";
        String hash1 = VnpaySigner.sign(qs, SECRET);
        String hash2 = VnpaySigner.sign(qs, SECRET);
        assertEquals(hash1, hash2, "same input must produce same hash");
    }

    @Test
    void signChangesWithDifferentSecret() {
        String qs = "vnp_Amount=10000000";
        String hash1 = VnpaySigner.sign(qs, "secret1");
        String hash2 = VnpaySigner.sign(qs, "secret2");
        assertNotEquals(hash1, hash2);
    }
}
