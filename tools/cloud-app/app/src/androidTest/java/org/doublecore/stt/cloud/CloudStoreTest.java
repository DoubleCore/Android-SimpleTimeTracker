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
    public void testFixedChineseCatalogIsStableAndIdempotent() throws Exception {
        String device = UUID.randomUUID().toString();
        FixedActivities.ensure(store, device);
        assertEquals(FixedActivities.ALL.size(), store.view("activities").size());
        int queued = store.pending().size();
        assertEquals(FixedActivities.ALL.size(), queued);

        FixedActivities.ensure(store, device);
        assertEquals(queued, store.pending().size());
        for (FixedActivities.Entry entry : FixedActivities.ALL) {
            assertEquals(entry.name, store.view("activities").get(entry.id).getString("name"));
            assertEquals(entry.id, UUID.nameUUIDFromBytes(
                ("timetrak:activity:v1:" + entry.name)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        }
        store.close();
        store = new CloudStore(getContext(), name);
        FixedActivities.ensure(store, UUID.randomUUID().toString());
        assertEquals(queued, store.pending().size());
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

    public void testSyncNeverDropsQueueOnAuthFailureOrMalformedAck() throws Exception {
        JSONObject command = operation("activity").put("activity_id", UUID.randomUUID().toString()).put("name", "学习");
        store.enqueue(command);
        CloudHttp rejectedAuth = new CloudHttp() {
            @Override public Response request(String base, String token, String path, JSONObject body) throws Exception {
                return new Response(401, new JSONObject().put("error", "unauthorized"));
            }
        };
        try { new CloudSync(rejectedAuth).run(store, "https://example.com", "token"); fail("401 accepted"); }
        catch (java.io.IOException expected) { }
        assertEquals(1, store.pending().size());
        CloudHttp malformedAck = new CloudHttp() {
            @Override public Response request(String base, String token, String path, JSONObject body) throws Exception {
                JSONObject response = new JSONObject().put("protocol", 1).put("workspace_id", workspace);
                if (body != null) response.put("accepted", true).put("operation_id", UUID.randomUUID().toString());
                return new Response(200, response);
            }
        };
        try { new CloudSync(malformedAck).run(store, "https://example.com", "token"); fail("wrong ACK accepted"); }
        catch (java.io.IOException expected) { }
        assertEquals(1, store.pending().size());
    }

    public void testLostAckRetriesSameIdAndEventuallyPullsCanonicalRecord() throws Exception {
        String activityId = UUID.randomUUID().toString();
        JSONObject command = operation("activity").put("activity_id", activityId).put("name", "学习");
        store.enqueue(command);
        final String[] accepted = {null};
        final int[] sends = {0};
        CloudHttp server = new CloudHttp() {
            @Override public Response request(String base, String token, String path, JSONObject body) throws Exception {
                if (path.equals("/v1/health")) return new Response(200, new JSONObject().put("protocol", 1).put("workspace_id", workspace));
                if (body != null) {
                    sends[0]++;
                    if (accepted[0] == null) {
                        accepted[0] = body.getString("id");
                        throw new java.io.IOException("response lost after server commit");
                    }
                    assertEquals(accepted[0], body.getString("id"));
                    return new Response(200, new JSONObject().put("protocol", 1).put("workspace_id", workspace)
                            .put("operation_id", accepted[0]).put("accepted", true));
                }
                return new Response(200, page(1, new JSONArray().put(event(1, "activity", new JSONObject().put("id", activityId).put("name", "学习")))));
            }
        };
        try { new CloudSync(server).run(store, "https://example.com", "token"); fail("lost response accepted"); }
        catch (java.io.IOException expected) { }
        store.close(); store = new CloudStore(getContext(), name);
        assertTrue(new CloudSync(server).run(store, "https://example.com", "token"));
        assertEquals(2, sends[0]); assertEquals(0, store.pending().size());
        assertEquals(1L, store.cursor()); assertEquals(1, store.view("activities").size());
        assertFalse(store.view("activities").get(activityId).optBoolean("pending"));
    }

    public void testCloudScreenLaunchesWithoutGoogleServices() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Intent intent = new android.content.Intent(getContext(), CloudActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.Activity activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        assertNotNull(activity.findViewById(android.R.id.content));
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }
}
