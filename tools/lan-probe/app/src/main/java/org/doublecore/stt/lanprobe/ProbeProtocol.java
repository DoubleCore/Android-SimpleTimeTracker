package org.doublecore.stt.lanprobe;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;

/** M0 only: no business data, Google APIs, DNS lookup or public destinations. */
public final class ProbeProtocol {
    public static final int PORT = 8765;
    public static final int TIMEOUT_MS = 3000;
    public static final int MAX_BYTES = 4096;

    private ProbeProtocol() { }

    public static String randomHex() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >>> 4) & 15, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }

    public static boolean validToken(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    public static String response(String nonce) {
        return "{\"probe\":\"stt-lan-m0\",\"protocol\":1,\"nonce\":\"" + nonce + "\"}";
    }

    public static InetAddress privateAddress(String value) throws UnknownHostException {
        // Strict numeric input avoids DNS, redirects and ambiguous URL parsing.
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IllegalArgumentException("请输入手机的局域网 IPv4，不含 http:// 或端口");
        }
        String[] parts = value.split("\\.");
        byte[] bytes = new byte[4];
        int[] numbers = new int[4];
        for (int i = 0; i < 4; i++) {
            numbers[i] = Integer.parseInt(parts[i]);
            if (numbers[i] > 255 || (parts[i].length() > 1 && parts[i].startsWith("0"))) {
                throw new IllegalArgumentException("IPv4 格式错误");
            }
            bytes[i] = (byte) numbers[i];
        }
        boolean privateIp = numbers[0] == 10
                || (numbers[0] == 172 && numbers[1] >= 16 && numbers[1] <= 31)
                || (numbers[0] == 192 && numbers[1] == 168);
        if (!privateIp) throw new IllegalArgumentException("M0 仅允许私有局域网 IPv4");
        return InetAddress.getByAddress(bytes);
    }
}
