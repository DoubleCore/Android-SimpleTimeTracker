package org.doublecore.stt.lanprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ProbeClient {
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    public long probe(String ip, String token) throws IOException {
        ProbeProtocol.privateAddress(ip);
        return request(new URL("http", ip, ProbeProtocol.PORT, "/health"), token);
    }

    // Package-private loopback seam for JVM integration tests only.
    long request(URL endpoint, String token) throws IOException {
        if (!ProbeProtocol.validToken(token)) throw new IllegalArgumentException("令牌必须是32位小写十六进制");
        if (cancelled) throw new IOException("cancelled");
        String nonce = ProbeProtocol.randomHex();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "?nonce=" + nonce)
                .openConnection(Proxy.NO_PROXY);
        active = connection;
        long started = System.nanoTime();
        try {
            if (cancelled) throw new IOException("cancelled");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(ProbeProtocol.TIMEOUT_MS);
            connection.setReadTimeout(ProbeProtocol.TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP_" + status);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("cancelled");
                    if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(10)) {
                        throw new IOException("response_deadline");
                    }
                    if (out.size() + count > ProbeProtocol.MAX_BYTES) throw new IOException("response_limit");
                    out.write(buffer, 0, count);
                }
            }
            if (!new String(out.toByteArray(), StandardCharsets.UTF_8).equals(ProbeProtocol.response(nonce))) {
                throw new IOException("PROTOCOL_MISMATCH");
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        } finally {
            connection.disconnect();
            active = null;
        }
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
