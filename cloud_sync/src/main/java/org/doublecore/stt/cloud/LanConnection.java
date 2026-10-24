package org.doublecore.stt.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Watch-side Wi-Fi client for the phone LAN receiver (protocol 2). No Google APIs.
 * Requests and responses are HMAC signed with the pairing secret (see {@link LanAuth}).
 */
public final class LanConnection {
    public static final int PORT = 8765;
    public static final int MAX_REQUEST_BYTES = 65536;
    private static final int MAX_RESPONSE_BYTES = 262144;
    /** Server clock minus watch clock, learned from a clock_skew rejection. */
    private static volatile long clockOffsetMs = 0L;
    private final SharedPreferences prefs;

    public LanConnection(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("stt_lan_direct_v1", Context.MODE_PRIVATE);
    }

    public String address() { return prefs.getString("address", ""); }
    public String token() { return prefs.getString("token", ""); }
    public boolean configured() { return !address().isEmpty() && LanAuth.isValidSecret(token()); }

    public void save(String address, String secret) {
        checkIp(address);
        prefs.edit().putString("address", address).putString("token", LanAuth.normalizeSecret(secret)).apply();
    }

    public void clear() { prefs.edit().clear().apply(); }

    public static boolean validToken(String token) { return LanAuth.isValidSecret(token); }

    public static void checkIp(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("请输入手机局域网 IPv4");
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 1 && parts[i].startsWith("0")) throw new IllegalArgumentException("IPv4格式错误");
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) throw new IllegalArgumentException("IPv4格式错误");
        }
        if (!(octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) || (octets[0] == 192 && octets[1] == 168)))
            throw new IllegalArgumentException("只允许局域网私有 IPv4");
    }

    public static boolean allowedTarget(String method, String target) {
        if (method.equals("GET")) return target.equals("/v2/state") || target.startsWith("/v2/state?");
        return method.equals("POST") && target.equals("/v2/ops");
    }

    public JSONObject request(String method, String target, JSONObject payload) throws Exception {
        try {
            return requestOnce(method, target, payload);
        } catch (ClockSkewException e) {
            // Correct once for watch/phone clock difference, then sign again with a new nonce.
            clockOffsetMs = e.serverTime - System.currentTimeMillis();
            return requestOnce(method, target, payload);
        }
    }

    private JSONObject requestOnce(String method, String target, JSONObject payload) throws Exception {
        if (!configured()) throw new IOException("先设置手机IP与配对密钥");
        if (!allowedTarget(method, target)) throw new IOException("非法接口");
        checkIp(address());
        String secret = token();
        byte[] bytes = payload == null ? new byte[0] : payload.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IOException("请求过大");
        long ts = System.currentTimeMillis() + clockOffsetMs;
        String nonce = LanAuth.newNonce();
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + address() + ":" + PORT + target).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty(LanAuth.HEADER_TS, Long.toString(ts));
            c.setRequestProperty(LanAuth.HEADER_NONCE, nonce);
            c.setRequestProperty(LanAuth.HEADER_SIG, LanAuth.signRequest(secret, method, target, ts, nonce, bytes));
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("拒绝网络重定向");
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new IOException("手机没有响应内容");
            byte[] body;
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > MAX_RESPONSE_BYTES) throw new IOException("响应过大");
                    out.write(buf, 0, n);
                }
                body = out.toByteArray();
            }
            JSONObject result = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (status == 401 && "clock_skew".equals(result.optString("error")) && result.has("server_time")) {
                throw new ClockSkewException(result.getLong("server_time"));
            }
            if (status == 401) throw new IOException("配对密钥不一致，请重新填写手机显示的密钥");
            String responseTs = c.getHeaderField(LanAuth.HEADER_TS);
            String responseSig = c.getHeaderField(LanAuth.HEADER_SIG);
            if (responseTs == null || responseSig == null ||
                    !LanAuth.constantTimeEquals(LanAuth.signResponse(secret, target, Long.parseLong(responseTs), nonce, body), responseSig)) {
                throw new IOException("手机响应签名无效");
            }
            if (result.optInt("protocol") != LanAuth.PROTOCOL) throw new IOException("手机端版本不兼容，请同时更新手机和手表");
            if (status != 200) throw new IOException("HTTP " + status + ": " + result.optString("error", "请求失败"));
            return result;
        } finally {
            c.disconnect();
        }
    }

    private static final class ClockSkewException extends IOException {
        final long serverTime;
        ClockSkewException(long serverTime) {
            super("clock_skew");
            this.serverTime = serverTime;
        }
    }
}
