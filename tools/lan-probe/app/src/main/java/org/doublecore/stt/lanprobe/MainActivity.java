package org.doublecore.stt.lanprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One separate APK on phone and watch. Native Android UI; no GMS/Wear libraries. */
public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<String> addresses = new ArrayList<>();
    private LinearLayout body;
    private TextView output;
    private Spinner addressPicker;
    private Button start;
    private Button test;
    private EditText phone;
    private EditText credential;
    private volatile ProbeServer server;
    private volatile ProbeClient client;
    private Future<?> pending;
    private int generation;
    private String serverToken;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Avoid restarting a foreground server on round-screen rotation/configuration changes.
        serverToken = ProbeProtocol.randomHex();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(getResources().getConfiguration().isScreenRound() ? 32 : 16);
        body.setPadding(inset, inset, inset, inset);
        scroll.addView(body);
        setContentView(scroll);
        label("STT LAN · M0 连通性验证", 20);
        label(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE, 12);
        label("仅用于可信测试局域网。退出界面即停止；不读写计时数据。手机选服务端，手表选客户端。", 14);
        LinearLayout serverPanel = panel();
        LinearLayout clientPanel = panel();
        Button phoneMode = button("手机：服务端", body);
        Button watchMode = button("手表：客户端", body);
        body.addView(serverPanel);
        body.addView(clientPanel);
        clientPanel.setVisibility(View.GONE);
        phoneMode.setOnClickListener(v -> {
            stopAll();
            serverPanel.setVisibility(View.VISIBLE);
            clientPanel.setVisibility(View.GONE);
        });
        watchMode.setOnClickListener(v -> {
            stopAll();
            serverPanel.setVisibility(View.GONE);
            clientPanel.setVisibility(View.VISIBLE);
        });

        TextView advice = new TextView(this);
        advice.setText("选择与手表互通的 Wi-Fi / 热点 IPv4；不要选 VPN 地址。端口固定 8765。");
        serverPanel.addView(advice);
        addressPicker = new Spinner(this);
        serverPanel.addView(addressPicker);
        button("刷新手机地址", serverPanel).setOnClickListener(v -> {
            stopAll();
            refreshAddresses();
        });
        TextView pairing = new TextView(this);
        pairing.setText("临时令牌（复制到手表）：\n" + serverToken);
        pairing.setTextIsSelectable(true);
        serverPanel.addView(pairing);
        start = button("启动手机测试服务", serverPanel);
        start.setOnClickListener(v -> startServer());
        button("停止服务 / 取消测试", serverPanel).setOnClickListener(v -> stopAll());

        phone = new EditText(this);
        phone.setHint("手机 IPv4，如 192.168.1.100");
        phone.setSingleLine(true);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        clientPanel.addView(phone);
        credential = new EditText(this);
        credential.setHint("手机上的32位临时令牌");
        credential.setSingleLine(true);
        credential.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        clientPanel.addView(credential);
        // Only an IP may be remembered. Pairing tokens never enter disk preferences or logs.
        phone.setText(getPreferences(MODE_PRIVATE).getString("phone_ip", ""));
        test = button("连续测试 5 次", clientPanel);
        test.setOnClickListener(v -> runProbes());
        button("取消测试", clientPanel).setOnClickListener(v -> stopAll());
        output = label("尚未测试。", 13);
        output.setTextIsSelectable(true);
        refreshAddresses();
    }

    private void refreshAddresses() {
        addresses.clear();
        List<String> choices = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    try {
                        String ip = address.getHostAddress();
                        ProbeProtocol.privateAddress(ip);
                        addresses.add(ip);
                        choices.add(network.getName() + " · " + ip);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (Exception e) {
            event("地址读取失败：" + e.getClass().getSimpleName());
        }
        if (choices.isEmpty()) choices.add("无私有 IPv4，请连接 Wi-Fi 或开启热点");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addressPicker.setAdapter(adapter);
    }

    private void startServer() {
        stopAll();
        if (addresses.isEmpty()) {
            event("未找到私有 IPv4。请联网后刷新。");
            return;
        }
        String ip = addresses.get(addressPicker.getSelectedItemPosition());
        int run = generation;
        start.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pending = io.submit(() -> {
            try {
                ProbeServer created = new ProbeServer(ProbeProtocol.privateAddress(ip),
                        ProbeProtocol.PORT, serverToken,
                        message -> runOnUiThread(() -> { if (run == generation) event(message); }));
                runOnUiThread(() -> {
                    if (run != generation) {
                        created.close();
                        return;
                    }
                    server = created;
                    event("LISTENING " + ip + ":8765；请保持手机页面打开");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (run == generation) {
                        start.setEnabled(true);
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        event("服务启动失败：" + e.getClass().getSimpleName());
                    }
                });
            }
        });
    }

    private void runProbes() {
        stopAll();
        String ip = phone.getText().toString().trim();
        String token = credential.getText().toString().trim();
        try {
            ProbeProtocol.privateAddress(ip);
            if (!ProbeProtocol.validToken(token)) throw new IllegalArgumentException("请填入32位小写十六进制令牌");
        } catch (Exception e) {
            event(e.getMessage());
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("phone_ip", ip).apply();
        int run = generation;
        ProbeClient probe = new ProbeClient();
        client = probe;
        test.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pending = io.submit(() -> {
            int successes = 0;
            for (int i = 1; i <= 5 && !Thread.currentThread().isInterrupted(); i++) {
                String result;
                try {
                    long latency = probe.probe(ip, token);
                    successes++;
                    result = "PASS " + i + "/5 · " + latency + " ms · nonce matched";
                } catch (Exception e) {
                    result = "FAIL " + i + "/5 · " + explain(e);
                }
                String line = result;
                runOnUiThread(() -> { if (run == generation) event(line); });
            }
            int passed = successes;
            runOnUiThread(() -> {
                if (run != generation) return;
                test.setEnabled(true);
                client = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                event("RESULT " + passed + "/5；仅代表本次前台 HTTP 测试");
            });
        });
    }

    private String explain(Exception error) {
        String message = error.getMessage();
        if ("HTTP_401".equals(message)) return "认证失败：重新复制手机令牌";
        if ("PROTOCOL_MISMATCH".equals(message)) return "响应不匹配：确认手机IP及探针版本";
        if (error instanceof java.net.SocketTimeoutException) return "超时：检查IP、网络隔离和手机页面";
        if (error instanceof java.net.ConnectException) return "无法连接：检查手机服务是否启动";
        if (message != null && message.startsWith("HTTP_")) return message;
        return error.getClass().getSimpleName();
    }

    private void stopAll() {
        generation++;
        ProbeClient activeClient = client;
        if (activeClient != null) activeClient.cancel();
        client = null;
        if (pending != null) pending.cancel(true);
        ProbeServer activeServer = server;
        if (activeServer != null) activeServer.close();
        server = null;
        if (start != null) start.setEnabled(true);
        if (test != null) test.setEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (output != null) event("STOPPED：服务和测试已停止");
    }

    private void event(String text) {
        // Never log headers, URLs containing credentials, or the pairing token.
        String line = System.currentTimeMillis() + " " + text;
        Log.i("SttLanM0", line);
        history.addLast(line);
        while (history.size() > 20) history.removeFirst();
        if (output != null) output.setText(android.text.TextUtils.join("\n", history));
    }

    private LinearLayout panel() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        body.addView(view);
        return view;
    }

    private Button button(String text, LinearLayout parent) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        parent.addView(view);
        return view;
    }

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }

    @Override protected void onStop() {
        stopAll();
        super.onStop();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
