package org.doublecore.stt.cloud;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;

/** Connection settings only: recording stays in the native phone/watch screens. */
public final class CloudSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(28 * getResources().getDisplayMetrics().density);
        root.setPadding(padding,padding,padding,padding); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("时间记录 · 云同步设置"); title.setTextSize(20); root.addView(title);
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("服务器 HTTPS 地址"); root.addView(address);
        EditText secret = new EditText(this); secret.setSingleLine(true);
        secret.setHint("手机、手表填写同一密钥"); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(secret);
        TextView status = new TextView(this); root.addView(status);
        try {
            CloudConfig config = new CloudConfig(this);
            address.setText(config.url.isEmpty() ? "https://stt-cloud-production.up.railway.app" : config.url);
            secret.setText(config.token);
            Button save = new Button(this); save.setText("保存并返回计时"); root.addView(save);
            save.setOnClickListener(v -> {
                try {
                    String url = CloudHttp.normalize(address.getText().toString());
                    if (!config.url.isEmpty() && !config.url.equals(url)) {
                        status.setText("此版本已绑定当前服务器；更换服务器前需迁移数据。"); return;
                    }
                    config.save(url,secret.getText().toString().trim()); finish();
                } catch(Exception e) { status.setText(e.getMessage()); }
            });
            Button details = new Button(this); details.setText("同步状态与待处理操作"); root.addView(details);
            details.setOnClickListener(v -> startActivity(new android.content.Intent(this,CloudActivity.class)));
            status.setText("正常计时在应用主界面进行。联网后同步，断网保留本地记录。手机已有计时历史将在连接后上传到这个私人服务器。");
        } catch(Exception e) { status.setText("配置读取失败：" + e.getMessage()); }
    }
}
