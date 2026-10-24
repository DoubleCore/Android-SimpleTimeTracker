package org.doublecore.stt.cloud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded HTTP/1.1 server for the phone LAN receiver (protocol 2).
 * Accepts only private-network peers, verifies HMAC request signatures with replay
 * protection and signs every authenticated response. Plain Java: no Android types,
 * so it can be exercised by JVM tests.
 */
public final class LanHttpServer implements AutoCloseable {

    public interface SecretProvider { String secret(); }

    public interface Handler {
        Response handle(String method, String target, byte[] body, String peer) throws Exception;
    }

    public static final class Response {
        public final int status;
        public final byte[] body;
        public Response(int status, byte[] body) { this.status = status; this.body = body; }
        public static Response json(int status, String json) {
            return new Response(status, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private final ServerSocket socket = new ServerSocket();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8));
    private final LanAuth.NonceCache nonces = new LanAuth.NonceCache(4096);
    private final SecretProvider secrets;
    private final Handler handler;
    private final boolean allowLoopback;
    private volatile boolean closed = false;

    /** Binds all interfaces so a Wi-Fi/hotspot address change does not need a restart; peers are filtered. */
    public LanHttpServer(int port, SecretProvider secrets, Handler handler) throws IOException {
        this(null, port, secrets, handler, false);
    }

    LanHttpServer(InetAddress bind, int port, SecretProvider secrets, Handler handler, boolean allowLoopback) throws IOException {
        this.secrets = secrets;
        this.handler = handler;
        this.allowLoopback = allowLoopback;
        socket.setReuseAddress(true);
        socket.bind(bind == null ? new InetSocketAddress(port) : new InetSocketAddress(bind, port), 16);
        Thread accept = new Thread(this::acceptLoop, "tt-lan-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() { return socket.getLocalPort(); }

    public boolean isOpen() { return !closed; }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket client = socket.accept();
                InetAddress remote = client.getInetAddress();
                boolean trusted = remote.isSiteLocalAddress() || (allowLoopback && remote.isLoopbackAddress());
                if (!trusted) { client.close(); continue; }
                try { pool.execute(() -> handle(client)); }
                catch (RejectedExecutionException e) { client.close(); }
            } catch (Exception e) {
                if (!closed) close();
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(5000);
            InputStream input = c.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int tail = 0;
            while (head.size() < MAX_HEADER_BYTES && tail != 0x0d0a0d0a) {
                int b = input.read();
                if (b < 0) return;
                head.write(b);
                tail = (tail << 8) | b;
            }
            if (tail != 0x0d0a0d0a) { error(c, 400, "header_too_large"); return; }
            String[] lines = head.toString("US-ASCII").split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length != 3 || !request[2].equals("HTTP/1.1")) { error(c, 400, "invalid_request"); return; }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                int idx = line.indexOf(':');
                if (idx <= 0) { error(c, 400, "invalid_header"); return; }
                String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                if (headers.put(name, line.substring(idx + 1).trim()) != null) { error(c, 400, "duplicate_header"); return; }
            }
            if (headers.containsKey("transfer-encoding")) { error(c, 400, "unsupported_encoding"); return; }
            String method = request[0];
            String target = request[1];
            if (!LanConnection.allowedTarget(method, target)) { error(c, 404, "unknown_route"); return; }
            byte[] body = new byte[0];
            String lengthHeader = headers.get("content-length");
            int length = lengthHeader == null ? 0 : parseLength(lengthHeader);
            if (length < 0 || length > LanConnection.MAX_REQUEST_BYTES) { error(c, 413, "invalid_length"); return; }
            if (method.equals("POST")) {
                if (length == 0) { error(c, 411, "length_required"); return; }
                body = new byte[length];
                int got = 0;
                while (got < length) {
                    int n = input.read(body, got, length - got);
                    if (n < 0) return;
                    got += n;
                }
            } else if (length != 0) { error(c, 400, "unexpected_body"); return; }

            String secret = secrets.secret();
            long now = System.currentTimeMillis();
            String tsHeader = headers.get(LanAuth.HEADER_TS.toLowerCase(Locale.ROOT));
            String nonce = headers.get(LanAuth.HEADER_NONCE.toLowerCase(Locale.ROOT));
            String sig = headers.get(LanAuth.HEADER_SIG.toLowerCase(Locale.ROOT));
            long ts;
            try { ts = Long.parseLong(tsHeader); } catch (Exception e) { error(c, 401, "unauthorized"); return; }
            if (secret == null || nonce == null || sig == null ||
                    !LanAuth.constantTimeEquals(LanAuth.signRequest(secret, method, target, ts, nonce, body), sig)) {
                error(c, 401, "unauthorized");
                return;
            }
            if (!LanAuth.withinWindow(ts, now)) {
                respondRaw(c, 401, ("{\"protocol\":2,\"error\":\"clock_skew\",\"server_time\":" + now + "}")
                        .getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            if (!nonces.firstUse(nonce, now)) { error(c, 401, "replay"); return; }

            Response response;
            try {
                response = handler.handle(method, target, body, c.getInetAddress().getHostAddress());
            } catch (Exception e) {
                response = Response.json(500, "{\"protocol\":2,\"error\":\"internal\"}");
            }
            long responseTs = System.currentTimeMillis();
            String responseSig = LanAuth.signResponse(secret, target, responseTs, nonce, response.body);
            respondRaw(c, response.status, response.body,
                    LanAuth.HEADER_TS + ": " + responseTs + "\r\n" + LanAuth.HEADER_SIG + ": " + responseSig + "\r\n");
        } catch (Exception ignored) {
            // Client went away or timed out; nothing was committed for this request.
        }
    }

    private static int parseLength(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return -1; }
    }

    private static void error(Socket c, int status, String code) throws IOException {
        respondRaw(c, status, ("{\"protocol\":2,\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8), null);
    }

    private static void respondRaw(Socket c, int status, byte[] body, String extraHeaders) throws IOException {
        String header = "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                (extraHeaders == null ? "" : extraHeaders) +
                "Content-Length: " + body.length + "\r\n\r\n";
        OutputStream out = c.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }
}
