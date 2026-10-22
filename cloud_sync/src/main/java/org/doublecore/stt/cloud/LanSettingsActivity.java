package org.doublecore.stt.cloud;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;

/** Watch-side pairing page for the direct phone-server transport. */
public final class LanSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad,pad,pad,pad);
        scroll.addView(root); setContentView(scroll);
        TextView title=new TextView(this);title.setText("Watch 5 · Wi-Fi直连手机");title.setTextSize(19);root.addView(title);
        TextView intro=new TextView(this);intro.setText("手机主界面打开 Wi-Fi直连服务，在同一个可信 Wi-Fi 下输入手机IP与32位密钥。无需云端或Google服务。");root.addView(intro);
        LanConnection conn=new LanConnection(this);
        EditText ip=new EditText(this);ip.setSingleLine(true);ip.setHint("手机IP，如192.168.1.20");ip.setInputType(InputType.TYPE_CLASS_PHONE);ip.setText(conn.address());root.addView(ip);
        EditText token=new EditText(this);token.setSingleLine(true);token.setHint("手机显示的32位密钥");token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setText(conn.token());root.addView(token);
        TextView status=new TextView(this);root.addView(status);
        Button save=new Button(this);save.setText("保存并连接");root.addView(save);
        save.setOnClickListener(v->{
            try {
                conn.save(ip.getText().toString().trim(),token.getText().toString().trim());
                status.setText("已保存，正在检查手机服务…");
                Executors.newSingleThreadExecutor().execute(()->{
                    try {
                        JSONObject data=conn.request("GET","/v1/state",null);
                        runOnUiThread(()->{status.setText("已连接 · 活动 "+data.optJSONArray("activities").length()+" 项");});
                    }catch(Exception e){runOnUiThread(()->status.setText("连接待恢复："+e.getMessage()));}
                });
            }catch(Exception e){status.setText(e.getMessage());}
        });
        Button back=new Button(this);back.setText("返回手表计时");back.setOnClickListener(v->finish());root.addView(back);
        Button clear=new Button(this);clear.setText("清除Wi-Fi直连配置（改用云端）");clear.setOnClickListener(v->{conn.clear();status.setText("已清除");});root.addView(clear);
    }
}
