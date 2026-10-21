package org.doublecore.stt.cloud;

import org.json.JSONObject;
import java.io.IOException;

public final class CloudSync {
    private final CloudHttp http;
    public CloudSync() { this(new CloudHttp()); }
    CloudSync(CloudHttp http) { this.http = http; }
    public boolean run(CloudStore store, String url, String token) throws Exception {
        CloudHttp.Response health = http.request(url, token, "/v1/health", null);
        requireOk(health); store.verifyWorkspace(health.body);
        for (JSONObject op : store.pending()) {
            CloudHttp.Response response = http.request(url, token, "/v1/operations", op);
            if (response.status == 200) {
                store.verifyWorkspace(response.body);
                if (!response.body.getBoolean("accepted") || !op.getString("id").equals(response.body.getString("operation_id"))) {
                    throw new IOException("服务器未确认此操作，保留待同步记录");
                }
                store.acknowledge(op.getString("id"));
            } else if (response.status == 400 || response.status == 409 || response.status == 413 || response.status == 415) {
                store.reject(op.getString("id"), response.body.optString("error", "HTTP " + response.status));
            } else { requireOk(response); }
        }
        // Bound work per foreground pass; next pass continues from the committed cursor.
        for (int i = 0; i < 20; i++) {
            CloudHttp.Response page = http.request(url, token, "/v1/sync?after=" + store.cursor(), null);
            requireOk(page); store.applyPage(page.body);
            if (!page.body.getBoolean("has_more")) return true;
        }
        return false;
    }
    private static void requireOk(CloudHttp.Response response) throws IOException {
        if (response.status != 200) throw new IOException("HTTP " + response.status + ": " + response.body.optString("error", "同步失败"));
    }
}
