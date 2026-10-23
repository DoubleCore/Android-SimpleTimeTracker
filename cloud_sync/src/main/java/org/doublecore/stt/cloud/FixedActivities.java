package org.doublecore.stt.cloud;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fixed, shared Chinese activity catalog. No UI for editing these presets. */
public final class FixedActivities {
    private FixedActivities() {}

    public static final class Entry {
        public final String id, name;
        Entry(String name) {
            this.name = name;
            this.id = UUID.nameUUIDFromBytes(("timetrak:activity:v1:" + name)
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public static final List<Entry> ALL = Collections.unmodifiableList(Arrays.asList(
            new Entry("深度学习"),
            new Entry("科研工作"),
            new Entry("编程开发"),
            new Entry("课程学习"),
            new Entry("阅读"),
            new Entry("写作整理"),
            new Entry("会议交流"),
            new Entry("日常事务"),
            new Entry("运动"),
            new Entry("休息"),
            new Entry("生活"),
            new Entry("睡眠")
    ));

    public static JSONObject data(Entry entry) throws Exception {
        return new JSONObject()
                .put("id", entry.id).put("name", entry.name)
                .put("icon", "ic_book").put("color_id", 0).put("color_int", "")
                .put("default_duration", 0).put("note", "").put("hidden", false);
    }

    /**
     * Durable and safe to call repeatedly. The same preset has the same UUID on both devices.
     * Do not delete legacy/custom activities or their historical records.
     */
    public static void ensure(CloudStore store, String deviceId) throws Exception {
        Map<String, JSONObject> existing = store.view("activities");
        for (Entry entry : ALL) {
            if (existing.containsKey(entry.id)) continue;
            if (store.outstanding() >= 900) return; // Preserve room for the user's operations.
            JSONObject value = data(entry);
            JSONObject operation = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("device_id", deviceId)
                    .put("kind", "put_activity")
                    .put("occurred_at", System.currentTimeMillis())
                    .put("data", value);
            store.enqueue(operation);
            existing.put(entry.id, value);
        }
    }
}
