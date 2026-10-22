package org.doublecore.stt.cloud;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Kept in Android's private no-backup directory; never placed in logs, URLs or APKs. */
public final class CloudConfig {
    public String url = "", token = "", device = UUID.randomUUID().toString();
    private final AtomicFile file;
    public CloudConfig(Context context) throws Exception {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "stt-cloud-config.json"));
        if (file.getBaseFile().exists()) {
            JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            url = saved.getString("url"); token = saved.getString("token"); device = saved.getString("device");
        } else {
            // Existing settings always win; never silently switch a configured workspace.
            try (InputStream input = context.getAssets().open("stt-private-config.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONObject preset = new JSONObject(bytes.toString("UTF-8"));
                save(preset.getString("url"), preset.getString("token"));
            } catch (FileNotFoundException absent) {
                // Public builds retain normal connection settings.
            }
        }
    }
    void save(String address, String secret) throws Exception {
        String normalized = CloudHttp.normalize(address);
        if (!secret.matches("[a-zA-Z0-9]{32,256}")) throw new IllegalArgumentException("密钥须为服务器生成的 32～256 位字母/数字");
        JSONObject json = new JSONObject().put("url", normalized).put("token", secret).put("device", device);
        FileOutputStream out = file.startWrite();
        try { out.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
        url = normalized; token = secret;
    }
    public String databaseName() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder("stt-cloud-");
        for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return name + ".db";
    }
}
