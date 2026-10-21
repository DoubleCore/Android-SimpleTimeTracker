package org.doublecore.stt.cloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Same no-GMS cloud recording screen in phone, Wear and the lightweight universal APK. */
public final class CloudActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::sync;
    private CloudConfig config;
    private CloudStore store;
    private LinearLayout root, content, settings;
    private EditText address, secret, activityName;
    private TextView status;
    private boolean foreground, busy, destroyed;
    private int failureCount;
    private String lastMessage = "请填写服务器地址与密钥";

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // Credentials can be displayed while editing; exclude this window from screenshots/recents.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try {
            config = new CloudConfig(this);
            if (!config.url.isEmpty()) store = new CloudStore(this, config.databaseName());
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("配置无法读取，未清除本地数据：" + e.getMessage())
                    .setPositiveButton("关闭", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int horizontal = getResources().getConfiguration().screenWidthDp < 300 ? 28 : 20;
        root.setPadding(dp(horizontal), dp(38), dp(horizontal), dp(40));
        scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        TextView title = text(root, "云端计时", 22); title.setGravity(Gravity.CENTER);
        status = text(root, "", 14);
        button(root, "连接设置", () -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        settings = new LinearLayout(this); settings.setOrientation(LinearLayout.VERTICAL); root.addView(settings);
        address = input(settings, "https://time.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(config.url);
        secret = input(settings, "服务器密钥（两端相同）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(config.token);
        button(settings, "保存并连接", this::saveConfig);
        settings.setVisibility(store == null ? View.VISIBLE : View.GONE);
        button(root, "立即同步", this::sync);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content);
        text(root, "添加活动", 18);
        activityName = input(root, "例如：学习", InputType.TYPE_CLASS_TEXT);
        button(root, "创建活动", () -> {
            String name = activityName.getText().toString().trim();
            if (name.isEmpty() || name.length() > 80) { message("活动名需要 1～80 个字符"); return; }
            enqueue("activity", UUID.randomUUID().toString(), null, name);
        });
        text(root, "页面打开时自动同步；离线操作会保留，重新打开后补传。此入口的记录独立于旧版手机历史。", 12);
        render();
    }
    @Override protected void onStart() { super.onStart(); foreground = true; if (status != null) sync(); }
    @Override protected void onStop() { foreground = false; main.removeCallbacks(poll); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacks(poll);
        worker.execute(() -> { if (store != null) store.close(); }); worker.shutdown();
        super.onDestroy();
    }
    private void saveConfig() {
        if (busy) { message("正在同步，请稍后保存设置"); return; }
        try {
            String newUrl = CloudHttp.normalize(address.getText().toString());
            boolean different = !newUrl.equals(config.url);
            if (different && store != null && store.outstanding() > 0) {
                message("还有待同步/待处理操作，请处理完成后更换服务器"); return;
            }
            config.save(newUrl, secret.getText().toString().trim());
            if (store == null || different) {
                if (store != null) store.close();
                store = new CloudStore(this, config.databaseName());
            }
            settings.setVisibility(View.GONE); failureCount = 0; sync();
        } catch (Exception e) { message(e.getMessage()); }
    }
    private void enqueue(String kind, String activityId, String sessionId, String name) {
        if (store == null) { message("先保存服务器设置；之后断网也能记录"); return; }
        try {
            JSONObject op = new JSONObject().put("id", UUID.randomUUID().toString())
                    .put("device_id", config.device).put("kind", kind).put("occurred_at", System.currentTimeMillis());
            if (activityId != null) op.put("activity_id", activityId);
            if (sessionId != null) op.put("session_id", sessionId);
            if (name != null) op.put("name", name);
            store.enqueue(op); lastMessage = "已保存在本机，等待服务器确认";
            if (kind.equals("activity")) activityName.setText("");
            render(); sync();
        } catch (Exception e) { message("本地保存失败：" + e.getMessage()); }
    }
    private void sync() {
        main.removeCallbacks(poll);
        if (!foreground || destroyed || store == null || busy) return;
        busy = true;
        final CloudStore current = store;
        final String url = config.url, token = config.token;
        worker.execute(() -> {
            String result; boolean ok;
            try {
                boolean complete = new CloudSync().run(current, url, token);
                ok = true;
                result = complete ? "已连接 · " + DateFormat.getTimeInstance().format(new Date()) : "正在补齐历史记录…";
            } catch (Exception e) {
                ok = false;
                result = "未同步：" + e.getMessage() + "\n本地操作已保留";
            }
            final String message = result; final boolean success = ok;
            main.post(() -> {
                busy = false;
                if (destroyed) return;
                failureCount = success ? 0 : Math.min(failureCount + 1, 4);
                lastMessage = message; render();
                if (foreground) main.postDelayed(poll, success ? 2000 : Math.min(30000, 2000L << failureCount));
            });
        });
    }
    private void render() {
        if (content == null) return;
        content.removeAllViews();
        if (store == null) { status.setText(lastMessage); return; }
        try {
            List<JSONObject> pending = store.pending(), rejected = store.rejected();
            String queue = pending.isEmpty() && rejected.isEmpty() ? "" : "\n待同步 " + pending.size() + " · 需处理 " + rejected.size();
            status.setText(lastMessage + queue);
            Map<String, JSONObject> activities = store.view("activities");
            Map<String, JSONObject> sessions = store.view("sessions");
            if (activities.isEmpty()) text(content, "先在任一设备创建活动，另一端会自动同步。", 15);
            for (JSONObject activity : activities.values()) {
                String id = activity.getString("id"), name = activity.getString("name");
                JSONObject running = null;
                for (JSONObject session : sessions.values()) {
                    if (id.equals(session.getString("activity_id")) && session.isNull("ended_at")) running = session;
                }
                if (running == null) {
                    button(content, "开始 · " + name + (activity.optBoolean("pending") ? "（待同步）" : ""),
                            () -> enqueue("start", id, UUID.randomUUID().toString(), null));
                } else {
                    String sessionId = running.getString("id");
                    text(content, name + " · " + duration(System.currentTimeMillis() - running.getLong("started_at"))
                            + (running.optBoolean("pending") ? " · 待同步" : " · 计时中"), 16);
                    button(content, "停止 · " + name, () -> enqueue("stop", null, sessionId, null));
                }
            }
            if (!rejected.isEmpty()) text(content, "需处理的操作", 18);
            for (JSONObject op : rejected) {
                String id = op.getString("id");
                text(content, op.getString("kind") + " · " + errorText(op.getString("error")) + "\n"
                        + DateFormat.getDateTimeInstance().format(new Date(op.getLong("occurred_at"))), 14);
                button(content, "确认忽略此失败操作", () -> new AlertDialog.Builder(this)
                        .setMessage("此操作未被服务器接受。忽略后不会自动重试，本机仍保留失败记录；需要时请重新操作。")
                        .setNegativeButton("返回", null).setPositiveButton("忽略", (d, w) -> { store.ignore(id); render(); }).show());
            }
            text(content, "最近记录（最多显示 50 条）", 18);
            List<JSONObject> history = new ArrayList<>();
            for (JSONObject session : sessions.values()) if (!session.isNull("ended_at")) history.add(session);
            java.util.Collections.sort(history, (a, b) -> Long.compare(b.optLong("started_at"), a.optLong("started_at")));
            for (int i = 0; i < Math.min(50, history.size()); i++) {
                JSONObject item = history.get(i), activity = activities.get(item.getString("activity_id"));
                text(content, (activity == null ? "活动" : activity.getString("name")) + " · "
                        + duration(item.getLong("ended_at") - item.getLong("started_at")) + "\n"
                        + DateFormat.getDateTimeInstance().format(new Date(item.getLong("started_at")))
                        + (item.optBoolean("pending") ? " · 待同步" : " · 已保存到服务器"), 14);
            }
        } catch (Exception e) { status.setText("本地数据读取失败：" + e.getMessage()); }
    }
    private static String errorText(String code) {
        switch (code) {
            case "activity_already_running": return "另一端已经开始此活动，请以同步后的状态为准";
            case "session_missing": return "对应开始操作未被接受，请核对记录";
            case "activity_missing": return "活动尚未创建成功";
            case "stop_before_start": case "invalid_clock": return "设备时间异常，请校准手机/手表时钟";
            default: return code;
        }
    }
    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private void message(String text) { lastMessage = text; if (status != null) status.setText(text); }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6)); parent.addView(view); return view;
    }
    private EditText input(LinearLayout parent, String hint, int type) {
        EditText view = new EditText(this); view.setHint(hint); view.setInputType(type); view.setSingleLine(true);
        view.setTextSize(16); view.setMinHeight(dp(48));
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(48));
        view.setOnClickListener(v -> action.run()); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
}
