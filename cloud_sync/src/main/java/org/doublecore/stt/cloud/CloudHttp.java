package org.doublecore.stt.cloud;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS only, platform certificate verification, no credential-bearing redirects. */
public class CloudHttp {
    public static String normalize(String value) throws Exception {
        URI uri = new URI(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || uri.getPort() == 0 || uri.getPort() < -1) {
            throw new IOException("请输入 https://服务器域名（可带端口，不含路径）");
        }
        return "https://" + uri.getRawAuthority().toLowerCase(java.util.Locale.ROOT);
    }
    public static final class Response {
        public final int status; public final JSONObject body;
        Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }
    public Response request(String base, String token, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(normalize(base) + path).openConnection();
        try {
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("服务器重定向被拒绝，请填写最终 HTTPS 地址");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                if (in == null) throw new IOException("HTTP " + status);
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 1048576) throw new IOException("服务器响应过大");
                    bytes.write(buffer, 0, count);
                }
            }
            return new Response(status, new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } finally { connection.disconnect(); }
    }
}
