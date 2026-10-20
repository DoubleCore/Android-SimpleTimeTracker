package org.doublecore.stt.lanprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A bounded health-only HTTP probe. It must never become the business gateway. */
public final class ProbeServer implements AutoCloseable {
    public interface Listener { void event(String message); }
    private final ServerSocket listener;
    private final String token;
    private final Listener events;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed;

    public ProbeServer(InetAddress address, int port, String token, Listener events) throws IOException {
        if (!ProbeProtocol.validToken(token)) throw new IllegalArgumentException("Invalid token");
        this.token = token;
        this.events = events;
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new java.net.InetSocketAddress(address, port), 4);
        } catch (IOException e) {
            listener.close();
            workers.shutdownNow();
            throw e;
        }
        new Thread(this::accept, "m0-accept").start();
    }

    public int port() { return listener.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                sockets.add(socket);
                if (closed) {
                    discard(socket);
                    return;
                }
                try {
                    workers.execute(() -> handle(socket));
                } catch (RejectedExecutionException e) {
                    discard(socket); // Hard connection limit, no unbounded overload work.
                }
            } catch (IOException e) {
                if (!closed) events.event("ACCEPT_ERROR " + e.getClass().getSimpleName());
                close();
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket ignored = socket) {
            String header;
            try {
                header = readHeader(socket);
            } catch (HeaderTooLarge e) {
                reply(socket, 431, "Request Header Fields Too Large", "header_limit");
                return;
            }
            String[] lines = header.split("\r\n");
            String[] request = lines[0].split(" ", -1);
            if (request.length != 3 || !request[2].equals("HTTP/1.1")) {
                reply(socket, 400, "Bad Request", "bad_request");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1) {
                    reply(socket, 400, "Bad Request", "bad_header");
                    return;
                }
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9-]+")
                        || headers.put(key, lines[i].substring(colon + 1).trim()) != null) {
                    reply(socket, 400, "Bad Request", "duplicate_or_invalid_header");
                    return;
                }
            }
            String authorization = headers.getOrDefault("authorization", "");
            if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                    authorization.getBytes(StandardCharsets.US_ASCII))) {
                reply(socket, 401, "Unauthorized", "unauthorized");
                return;
            }
            if (!request[0].equals("GET")) {
                reply(socket, 405, "Method Not Allowed", "get_only");
                return;
            }
            if (headers.containsKey("transfer-encoding")
                    || !headers.getOrDefault("content-length", "0").equals("0")) {
                reply(socket, 400, "Bad Request", "body_not_allowed");
                return;
            }
            if (!request[1].matches("/health\\?nonce=[0-9a-f]{32}")) {
                reply(socket, 404, "Not Found", "unknown_route");
                return;
            }
            String nonce = request[1].substring("/health?nonce=".length());
            reply(socket, 200, "OK", ProbeProtocol.response(nonce));
            events.event("HEALTH_OK peer=" + socket.getInetAddress().getHostAddress());
        } catch (IOException e) {
            if (!closed) events.event("REQUEST_ERROR " + e.getClass().getSimpleName());
        } finally {
            sockets.remove(socket);
        }
    }

    private String readHeader(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ProbeProtocol.TIMEOUT_MS);
        int tail = 0;
        while (out.size() < ProbeProtocol.MAX_BYTES) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("header_deadline");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int b = input.read();
            if (b == -1) throw new IOException("incomplete_header");
            out.write(b);
            tail = (tail << 8) | b;
            if (tail == 0x0d0a0d0a) return out.toString("US-ASCII");
        }
        throw new HeaderTooLarge();
    }

    private void reply(Socket socket, int status, String reason, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private void discard(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            sockets.clear();
        }
        workers.shutdownNow();
    }

    private static final class HeaderTooLarge extends IOException { }
}
