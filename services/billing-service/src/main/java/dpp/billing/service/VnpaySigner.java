package dpp.billing.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * VNPAY HMAC-SHA512 signing utility (task 21.2, R33.2).
 *
 * <p>Builds the canonical querystring from sorted vnp_* parameters (excluding
 * vnp_SecureHash), URL-encodes values using RFC 3986 (UTF-8, spaces as %20),
 * and signs with HMAC-SHA512. The same sort + encode logic is used for both
 * signing (createPaymentUrl) and verification (return/IPN) so they are
 * guaranteed consistent.</p>
 */
public final class VnpaySigner {

    private VnpaySigner() {
    }

    /**
     * Build the canonical sorted querystring (excluding vnp_SecureHash) from the
     * given parameters. Keys are sorted ascending; values are URL-encoded with
     * UTF-8 using %20 for spaces (VNPAY convention).
     */
    public static String buildQueryString(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().equals("vnp_SecureHash") || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * Sign the querystring with HMAC-SHA512 using the given secret.
     * Returns the hex lowercase digest.
     */
    public static String sign(String queryString, String hashSecret) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec key = new SecretKeySpec(
                    hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(key);
            byte[] hashBytes = hmac512.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA512 signing failed", e);
        }
    }

    /**
     * Verify a vnp_SecureHash against the given parameters. Returns true if the
     * recomputed hash matches the provided secureHash (case-insensitive).
     */
    public static boolean verify(Map<String, String> params, String secureHash, String hashSecret) {
        if (secureHash == null || secureHash.isEmpty()) {
            return false;
        }
        String queryString = buildQueryString(params);
        String computed = sign(queryString, hashSecret);
        return computed.equalsIgnoreCase(secureHash);
    }

    /** URL-encode with UTF-8, using %20 for spaces (not +). */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
