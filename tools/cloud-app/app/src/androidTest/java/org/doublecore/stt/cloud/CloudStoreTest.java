package org.doublecore.stt.cloud;

import junit.framework.TestCase;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class CloudStoreTest extends TestCase {
    private Context getContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private CloudStore store;
    private String name;
    private String workspace;
    @Override protected void setUp() throws Exception {
        super.setUp(); name = "test-" + UUID.randomUUID() + ".db";
        workspace = UUID.randomUUID().toString(); store = new CloudStore(getContext(), name);
    }
    @Override protected void tearDown() throws Exception {
        store.close(); new File(getContext().getNoBackupFilesDir(), name).delete(); super.tearDown();
    }
    private JSONObject operation(String kind) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("device_id", UUID.randomUUID().toString())
                .put("kind", kind).put("occurred_at", System.currentTimeMillis());
    }
    private JSONObject page(long cursor, JSONArray events) throws Exception {
        return new JSONObject().put("protocol", 1).put("workspace_id", workspace)
                .put("cursor", cursor).put("events", events).put("has_more", false);
    }
    private JSONObject event(long seq, String entity, JSONObject data) throws Exception {
        return new JSONObject().put("seq", seq).put("entity", entity).put("data", data);
    }
    public void testOfflineStartStopSurviveProcessRestart() throws Exception {
        String activity = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
        store.enqueue(operation("activity").put("activity_id", activity).put("name", "学习"));
        store.enqueue(operation("start").put("activity_id", activity).put("session_id", session));
        store.enqueue(operation("stop").put("session_id", session));
        store.close(); store = new CloudStore(getContext(), name);
        assertEquals(3, store.pending().size());
        assertEquals("学习", store.view("activities").get(activity).getString("name"));
        assertFalse(store.view("sessions").get(session).isNull("ended_at"));
        assertTrue(store.view("sessions").get(session).getBoolean("pending"));
    }
    public void testPageAndCursorCommitTogetherAndRollbackOnGap() throws Exception {
        JSONObject data = new JSONObject().put("id", "one").put("name", "学习");
        store.applyPage(page(1, new JSONArray().put(event(1, "activity", data))));
        assertEquals(1L, store.cursor());
        JSONArray invalid = new JSONArray().put(event(2, "activity", new JSONObject().put("id", "two").put("name", "工作")))
                .put(event(4, "activity", data));
        try { store.applyPage(page(4, invalid)); fail("gap accepted"); } catch (org.json.JSONException expected) { }
        assertEquals(1L, store.cursor());
        assertFalse(store.view("activities").containsKey("two"));
        store.close(); store = new CloudStore(getContext(), name);
        assertEquals(1L, store.cursor());
    }
    public void testWrongWorkspaceDoesNotMixData() throws Exception {
        store.applyPage(page(0, new JSONArray()));
        workspace = UUID.randomUUID().toString();
        try { store.applyPage(page(0, new JSONArray())); fail("wrong workspace accepted"); }
        catch (org.json.JSONException expected) { }
    }
    public void testAckRejectionAndExplicitIgnore() throws Exception {
        JSONObject one = operation("start").put("activity_id", "a").put("session_id", "s");
        JSONObject two = operation("stop").put("session_id", "s");
        store.enqueue(one); store.enqueue(two);
        store.acknowledge(one.getString("id"));
        store.reject(two.getString("id"), "session_missing");
        assertEquals(0, store.pending().size()); assertEquals(1, store.rejected().size());
        assertEquals(1, store.outstanding());
        store.ignore(two.getString("id")); assertEquals(0, store.outstanding());
    }
    public void testTransportRefusesPlaintextAndCredentialRedirectOrigins() throws Exception {
        assertEquals("https://example.com", CloudHttp.normalize("https://EXAMPLE.com/"));
        for (String url : new String[]{"http://example.com", "https://user:password@example.com", "https://example.com/path", "https://example.com/?token=x"}) {
            try { CloudHttp.normalize(url); fail("unsafe origin accepted"); } catch (java.io.IOException expected) { }
        }
    }
}
