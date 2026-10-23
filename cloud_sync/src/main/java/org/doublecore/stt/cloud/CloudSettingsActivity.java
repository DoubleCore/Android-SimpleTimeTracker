package org.doublecore.stt.cloud;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A single settings home for new TimeTrak capabilities; the timer stays on the main screen. */
public final class CloudSettingsActivity extends Activity {
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView line(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view);
        return view;
    }

    private void heading(LinearLayout parent, String title) { line(parent, title, 18); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        line(root, "TimeTrak · 设置", 22);
        heading(root, "云端同步");
        line(root, "手机和 Watch 5 使用同一私人服务器。设置一次后，平时在主界面记录时间。", 14);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("服务器 HTTPS 地址");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(address);
        EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setHint("服务器密钥（手机与手表相同）");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(secret);
        TextView status = line(root, "", 13);

        try {
            CloudConfig config = new CloudConfig(this);
            address.setText(config.url.isEmpty() ? "https://stt-cloud-production.up.railway.app" : config.url);
            secret.setText(config.token);
            Button save = new Button(this); save.setText("保存云同步连接"); save.setAllCaps(false); root.addView(save);
            save.setOnClickListener(v -> {
                try {
                    String url = CloudHttp.normalize(address.getText().toString());
                    if (!config.url.isEmpty() && !config.url.equals(url)) {
                        status.setText("当前设备已绑定服务器；请先迁移待同步数据，再更换地址。"); return;
                    }
                    config.save(url, secret.getText().toString().trim());
                    status.setText("云同步配置已保存。返回计时界面后自动尝试连接。");
                } catch (Exception e) { status.setText("保存失败：" + e.getMessage()); }
            });
            Button details = new Button(this); details.setText("查看同步详情与待处理操作");
            details.setAllCaps(false); root.addView(details);
            details.setOnClickListener(v -> startActivity(new Intent(this, CloudActivity.class)));
            status.setText(config.url.isEmpty() ? "尚未连接服务器" : "已保存云端地址；请在同步详情查看实际连接结果");
        } catch (Exception e) {
            status.setText("配置读取失败，本地记录未清除：" + e.getMessage());
        }

        heading(root, "中文活动 · 固定清单");
        StringBuilder names = new StringBuilder();
        for (FixedActivities.Entry entry : FixedActivities.ALL) {
            if (names.length() != 0) names.append(" · ");
            names.append(entry.name);
        }
        line(root, names.toString(), 14);
        line(root, "以上是源码中预留的中文活动名称。本版为保持原版计时页面不变，不会自动创建活动或修改现有卡片。", 12);

        heading(root, "语音描述");
        line(root, "为保持原版描述编辑框外观不变，已撤回额外的麦克风按钮；语音识别代码保留但本版不提供新入口。", 14);

        heading(root, "局域网连接");
        line(root, "现有 LAN 探针为独立工程，尚未整合进正式计时应用。手机后台常驻局域网服务尚未完成。", 14);

        heading(root, "AI 自动分类");
        line(root, "规划使用手机连接你的模型 API，仅从固定中文活动中匹配分类；当前版本尚未执行自动分类，因此不展示无效的 API 配置。", 14);
    }
}
