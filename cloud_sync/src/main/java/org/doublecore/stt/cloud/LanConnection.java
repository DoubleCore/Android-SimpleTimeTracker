package org.doublecore.stt.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Direct, foreground-only Wi-Fi connection to the STT phone app. No Google APIs. */
public final class LanConnection {
    public static final int PORT = 8765;
    private final SharedPreferences prefs;
    public LanConnection(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("stt_lan_direct_v1", Context.MODE_PRIVATE);
    }
    public String address() { return prefs.getString("address", ""); }
    public String token() { return prefs.getString("token", ""); }
    public boolean configured() { return !address().isEmpty() && validToken(token()); }
    public void save(String address, String token) {
        checkIp(address);
        if (!validToken(token)) throw new IllegalArgumentException("请输入手机上显示的32位配对密钥");
        prefs.edit().putString("address", address).putString("token", token).apply();
    }
    public void clear() { prefs.edit().clear().apply(); }
    public static boolean validToken(String token) { return token != null && token.matches("[0-9a-f]{32}"); }
    public static void checkIp(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("请输入手机局域网 IPv4");
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int i=0;i<4;i++) {
            if (parts[i].length()>1 && parts[i].startsWith("0")) throw new IllegalArgumentException("IPv4格式错误");
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i]>255) throw new IllegalArgumentException("IPv4格式错误");
        }
        if (!(octets[0]==10 || (octets[0]==172 && octets[1]>=16 && octets[1]<=31) || (octets[0]==192 && octets[1]==168)))
            throw new IllegalArgumentException("只允许局域网私有 IPv4");
    }
    public JSONObject request(String method, String path, JSONObject payload) throws Exception {
        if (!configured()) throw new IOException("先设置手机IP与配对密钥");
        if (!path.equals("/v1/state") && !path.equals("/v1/action")) throw new IOException("非法接口");
        if (!(method.equals("GET") || method.equals("POST"))) throw new IOException("非法方法");
        checkIp(address());
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + address() + ":" + PORT + path).openConnection();
        try {
            c.setConnectTimeout(3000); c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + token());
            c.setRequestProperty("Accept", "application/json");
            c.setRequestMethod(method);
            if (payload != null) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 4096) throw new IOException("请求过大");
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out=c.getOutputStream()) { out.write(bytes); }
            }
            int status=c.getResponseCode();
            if (status>=300 && status<400) throw new IOException("拒绝网络重定向");
            InputStream stream = status>=400 ? c.getErrorStream() : c.getInputStream();
            if (stream==null) throw new IOException("服务器没有响应内容");
            try (InputStream in=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buf=new byte[1024]; int n;
                while ((n=in.read(buf))!=-1) { if (out.size()+n>131072) throw new IOException("响应过大"); out.write(buf,0,n); }
                JSONObject result=new JSONObject(out.toString("UTF-8"));
                if (status!=200) throw new IOException("HTTP "+status+": "+result.optString("error","请求失败"));
                if (result.optInt("protocol")!=1) throw new IOException("LAN协议不兼容");
                return result;
            }
        } finally { c.disconnect(); }
    }
}
