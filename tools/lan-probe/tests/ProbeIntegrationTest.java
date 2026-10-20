package org.doublecore.stt.lanprobe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Real TCP integration tests, runnable using JDK 17 without Android or dependencies. */
public final class ProbeIntegrationTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        String token = ProbeProtocol.randomHex();
        check(ProbeProtocol.validToken(token), "128-bit random token format");
        for (String ip : new String[]{"10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.2"}) {
            check(ProbeProtocol.privateAddress(ip).getHostAddress().equals(ip), "private IP " + ip);
        }
        for (String ip : new String[]{"8.8.8.8", "127.0.0.1", "0.0.0.0", "172.32.1.1",
                "192.168.01.1", "192.168.1.256", "localhost", "192.168.1.1:80", "http://192.168.1.1"}) {
            try {
                ProbeProtocol.privateAddress(ip);
                throw new AssertionError("accepted " + ip);
            } catch (IllegalArgumentException expected) { assertions++; }
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        AtomicReference<String> log = new AtomicReference<>("");
        int port;
        try (ProbeServer server = new ProbeServer(loopback, 0, token, log::set)) {
            port = server.port();
            URL endpoint = new URL("http://127.0.0.1:" + port + "/health");
            for (int i = 0; i < 5; i++) {
                check(new ProbeClient().request(endpoint, token) >= 0, "real HTTP round trip " + (i + 1));
            }
            expectClientFailure(endpoint, ProbeProtocol.randomHex(), "HTTP_401");
            String path = "/health?nonce=" + ProbeProtocol.randomHex();
            check(raw(port, "GET " + path + " HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 401"), "missing auth");
            check(raw(port, request("GET", "/stt/START_ACTIVITY", token, "")).startsWith("HTTP/1.1 404"), "no business routes");
            check(raw(port, request("POST", path, token, "")).startsWith("HTTP/1.1 405"), "no mutations");
            check(raw(port, request("GET", path, token, "Content-Length: 1\r\n")).startsWith("HTTP/1.1 400"), "no body");
            check(raw(port, request("GET", path, token, "Transfer-Encoding: chunked\r\n")).startsWith("HTTP/1.1 400"), "no chunked body");
            check(raw(port, request("GET", path, token, "Authorization: Bearer " + token + "\r\n")).startsWith("HTTP/1.1 400"), "no duplicate auth");
            check(raw(port, request("GET", path, token, "X-Pad: " + repeat("x", 4100) + "\r\n")).startsWith("HTTP/1.1 431"), "bounded headers");
            check(!log.get().contains(token), "no token in events");
            Socket pending = new Socket(loopback, port);
            pending.setSoTimeout(1500);
            // Close must unblock pending reader/acceptor; no background leak after Activity stops.
            pending.getOutputStream().write("GET /".getBytes(StandardCharsets.US_ASCII));
            server.close();
            try {
                check(pending.getInputStream().read() == -1, "shutdown closes incomplete request");
            } catch (java.net.SocketException expected) { assertions++; }
            pending.close();
        }
        try (ServerSocket rebound = new ServerSocket(port, 4, loopback)) {
            check(rebound.isBound(), "port can be rebound after stop");
        }
        fakeResponse("302 Found", "", "HTTP_302");
        fakeResponse("200 OK", "{\"probe\":\"wrong-server\"}", "PROTOCOL_MISMATCH");
        fakeResponse("200 OK", repeat("x", 4200), "response_limit");
        System.out.println("PASS: " + assertions + " assertions; real loopback TCP, not Watch 5 evidence.");
    }

    private static void fakeResponse(String status, String body, String expected) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread peer = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    int tail = 0;
                    int value;
                    while ((value = socket.getInputStream().read()) != -1) {
                        tail = (tail << 8) | value;
                        if (tail == 0x0d0a0d0a) break;
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\n"
                            + "Location: http://127.0.0.1:1/\r\nContent-Length: " + bytes.length
                            + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                } catch (Throwable e) { failure.set(e); }
            });
            peer.start();
            expectClientFailure(new URL("http://127.0.0.1:" + listener.getLocalPort() + "/health"),
                    ProbeProtocol.randomHex(), expected);
            peer.join(4000);
            check(!peer.isAlive() && failure.get() == null, "fake peer terminated");
        }
    }

    private static void expectClientFailure(URL endpoint, String token, String expected) throws Exception {
        try {
            new ProbeClient().request(endpoint, token);
            throw new AssertionError("expected " + expected);
        } catch (java.io.IOException e) {
            check(expected.equals(e.getMessage()), "client error " + expected + ", got " + e.getMessage());
        }
    }

    private static String raw(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private static String request(String method, String path, String token, String headers) {
        return method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                + token + "\r\n" + headers + "\r\n";
    }

    private static String repeat(String value, int count) {
        return String.join("", java.util.Collections.nCopies(count, value));
    }

    private static void check(boolean result, String name) {
        if (!result) throw new AssertionError(name);
        assertions++;
    }
}
