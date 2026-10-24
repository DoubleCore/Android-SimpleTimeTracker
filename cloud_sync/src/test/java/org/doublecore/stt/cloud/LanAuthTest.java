package org.doublecore.stt.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LanAuthTest {

    private static final String SECRET = "00112233445566778899aabbccddeeff";
    private LanHttpServer server;
    private final AtomicInteger handled = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        server = new LanHttpServer(InetAddress.getLoopbackAddress(), 0, () -> SECRET, (method, target, body, peer) -> {
            handled.incrementAndGet();
            return LanHttpServer.Response.json(200, "{\"protocol\":2,\"echo\":\"" + method + " " + target + "\"}");
        }, true);
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void normalizeSecretAcceptsGroupedInput() {
        assertEquals(SECRET, LanAuth.normalizeSecret("0011 2233-4455 6677 8899 AABB CCDD EEFF"));
        assertEquals("0011 2233 4455 6677 8899 aabb ccdd eeff", LanAuth.formatSecret(SECRET));
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeSecretRejectsShortInput() {
        LanAuth.normalizeSecret("0011");
    }

    @Test
    public void signatureCoversEveryField() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String base = LanAuth.signRequest(SECRET, "POST", "/v2/ops", 1000L, "aaaaaaaaaaaaaaaa", body);
        assertEquals(base, LanAuth.signRequest(SECRET, "POST", "/v2/ops", 1000L, "aaaaaaaaaaaaaaaa", body));
        assertNotEquals(base, LanAuth.signRequest(SECRET, "GET", "/v2/ops", 1000L, "aaaaaaaaaaaaaaaa", body));
        assertNotEquals(base, LanAuth.signRequest(SECRET, "POST", "/v2/state", 1000L, "aaaaaaaaaaaaaaaa", body));
        assertNotEquals(base, LanAuth.signRequest(SECRET, "POST", "/v2/ops", 1001L, "aaaaaaaaaaaaaaaa", body));
        assertNotEquals(base, LanAuth.signRequest(SECRET, "POST", "/v2/ops", 1000L, "bbbbbbbbbbbbbbbb", body));
        assertNotEquals(base, LanAuth.signRequest(SECRET, "POST", "/v2/ops", 1000L, "aaaaaaaaaaaaaaaa", "[]".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(base, LanAuth.signRequest("ffeeddccbbaa99887766554433221100", "POST", "/v2/ops", 1000L, "aaaaaaaaaaaaaaaa", body));
    }

    @Test
    public void nonceCacheRejectsReplay() {
        LanAuth.NonceCache cache = new LanAuth.NonceCache(10);
        assertTrue(cache.firstUse("aaaaaaaaaaaaaaaa", 0L));
        assertFalse(cache.firstUse("aaaaaaaaaaaaaaaa", 1L));
        assertFalse(cache.firstUse("not-hex", 1L));
    }

    @Test
    public void signedRequestIsServedAndResponseIsSigned() throws Exception {
        String nonce = LanAuth.newNonce();
        Raw response = send("GET", "/v2/state", new byte[0], System.currentTimeMillis(), nonce, SECRET);
        assertEquals(200, response.status);
        assertEquals(1, handled.get());
        String expected = LanAuth.signResponse(SECRET, "/v2/state", Long.parseLong(response.header(LanAuth.HEADER_TS)), nonce, response.body);
        assertTrue(LanAuth.constantTimeEquals(expected, response.header(LanAuth.HEADER_SIG)));
    }

    @Test
    public void wrongSecretIsRejectedBeforeHandler() throws Exception {
        Raw response = send("GET", "/v2/state", new byte[0], System.currentTimeMillis(), LanAuth.newNonce(), "ffeeddccbbaa99887766554433221100");
        assertEquals(401, response.status);
        assertEquals(0, handled.get());
    }

    @Test
    public void replayedRequestIsRejected() throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = LanAuth.newNonce();
        assertEquals(200, send("GET", "/v2/state", new byte[0], ts, nonce, SECRET).status);
        assertEquals(401, send("GET", "/v2/state", new byte[0], ts, nonce, SECRET).status);
        assertEquals(1, handled.get());
    }

    @Test
    public void staleTimestampReportsServerTime() throws Exception {
        Raw response = send("GET", "/v2/state", new byte[0], System.currentTimeMillis() - 3_600_000L, LanAuth.newNonce(), SECRET);
        assertEquals(401, response.status);
        assertTrue(new String(response.body, StandardCharsets.UTF_8).contains("clock_skew"));
        assertEquals(0, handled.get());
    }

    @Test
    public void unknownRouteIsRejected() throws Exception {
        Raw response = send("GET", "/v1/state", new byte[0], System.currentTimeMillis(), LanAuth.newNonce(), SECRET);
        assertEquals(404, response.status);
        assertEquals(0, handled.get());
    }

    @Test
    public void tamperedBodyIsRejected() throws Exception {
        byte[] body = "{\"ops\":[]}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        String nonce = LanAuth.newNonce();
        String sig = LanAuth.signRequest(SECRET, "POST", "/v2/ops", ts, nonce, body);
        byte[] tampered = "{\"ops\":[1]}".getBytes(StandardCharsets.UTF_8);
        Raw response = sendRaw("POST", "/v2/ops", tampered, ts, nonce, sig);
        assertEquals(401, response.status);
        assertEquals(0, handled.get());
    }

    private Raw send(String method, String target, byte[] body, long ts, String nonce, String secret) throws Exception {
        return sendRaw(method, target, body, ts, nonce, LanAuth.signRequest(secret, method, target, ts, nonce, body));
    }

    private Raw sendRaw(String method, String target, byte[] body, long ts, String nonce, String sig) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            String head = method + " " + target + " HTTP/1.1\r\n" +
                    "Host: test\r\n" +
                    LanAuth.HEADER_TS + ": " + ts + "\r\n" +
                    LanAuth.HEADER_NONCE + ": " + nonce + "\r\n" +
                    LanAuth.HEADER_SIG + ": " + sig + "\r\n" +
                    (body.length > 0 ? "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n" : "") +
                    "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
            return Raw.parse(all.toByteArray());
        }
    }

    private static final class Raw {
        final int status;
        final String headers;
        final byte[] body;

        Raw(int status, String headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            for (String line : headers.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).equalsIgnoreCase(name)) return line.substring(idx + 1).trim();
            }
            return null;
        }

        static Raw parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            String head = text.substring(0, split);
            int status = Integer.parseInt(head.split(" ")[1]);
            byte[] body = new byte[raw.length - split - 4];
            System.arraycopy(raw, split + 4, body, 0, body.length);
            return new Raw(status, head, body);
        }
    }
}
