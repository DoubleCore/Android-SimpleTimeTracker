package org.doublecore.stt.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared phone/watch LAN request signing (protocol 2).
 * The pairing secret never travels over the network: every request and response
 * carries an HMAC-SHA256 over method, target, timestamp, nonce and body hash.
 * Traffic is still unencrypted HTTP; only use on a trusted Wi-Fi or hotspot.
 */
public final class LanAuth {
    public static final int PROTOCOL = 2;
    public static final String HEADER_TS = "X-TT-Ts";
    public static final String HEADER_NONCE = "X-TT-Nonce";
    public static final String HEADER_SIG = "X-TT-Sig";
    /** Watch clocks can drift; the server reports its time so the client can retry once. */
    public static final long WINDOW_MS = 300_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LanAuth() {}

    /** Accepts upper case, spaces and dashes so the 32 hex characters can be typed in groups. */
    public static String normalizeSecret(String input) {
        if (input == null) throw new IllegalArgumentException("请输入手机上显示的32位配对密钥");
        String value = input.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (!isValidSecret(value)) throw new IllegalArgumentException("请输入手机上显示的32位配对密钥");
        return value;
    }

    public static boolean isValidSecret(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    public static String newSecret() {
        return randomHex(16);
    }

    public static String newNonce() {
        return randomHex(16);
    }

    /** Groups a secret as "abcd efgh ..." for display. */
    public static String formatSecret(String secret) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append(' ');
            out.append(secret.charAt(i));
        }
        return out.toString();
    }

    public static String signRequest(String secret, String method, String target, long ts, String nonce, byte[] body) {
        return hmac(secret, method + "\n" + target + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body));
    }

    /** Response signatures are bound to the request nonce, so an old response cannot be replayed. */
    public static String signResponse(String secret, String target, long ts, String requestNonce, byte[] body) {
        return hmac(secret, "RESPONSE\n" + target + "\n" + ts + "\n" + requestNonce + "\n" + sha256Hex(body));
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean withinWindow(long ts, long now) {
        return Math.abs(now - ts) <= WINDOW_MS;
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String secret, String canonical) {
        if (!isValidSecret(secret)) throw new IllegalArgumentException("invalid secret");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(unhex(secret), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return hex(value);
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Bounded replay protection; entries older than twice the window are dropped. */
    public static final class NonceCache {
        private final int capacity;
        private final LinkedHashMap<String, Long> seen;

        public NonceCache(final int capacity) {
            this.capacity = capacity;
            this.seen = new LinkedHashMap<String, Long>(capacity, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > NonceCache.this.capacity;
                }
            };
        }

        public synchronized boolean firstUse(String nonce, long now) {
            if (nonce == null || !nonce.matches("[0-9a-f]{16,64}")) return false;
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > WINDOW_MS * 2) it.remove(); else break;
            }
            if (seen.containsKey(nonce)) return false;
            seen.put(nonce, now);
            return true;
        }
    }
}
