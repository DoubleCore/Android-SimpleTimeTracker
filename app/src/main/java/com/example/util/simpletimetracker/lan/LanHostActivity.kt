package com.example.util.simpletimetracker.lan

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RecordsUpdateInteractor
import com.example.util.simpletimetracker.domain.record.interactor.UpdateRunningRecordsInteractor
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.recordType.repo.RecordTypeRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.doublecore.stt.cloud.LanConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Foreground-only phone HTTP host, writing directly into STT's original repositories. */
@AndroidEntryPoint
class LanHostActivity : ComponentActivity() {
    @Inject lateinit var types: RecordTypeRepo
    @Inject lateinit var running: RunningRecordInteractor
    @Inject lateinit var records: RecordInteractor
    @Inject lateinit var updates: RecordsUpdateInteractor
    @Inject lateinit var runningUpdates: UpdateRunningRecordsInteractor
    @Inject lateinit var cloudBridge: com.example.util.simpletimetracker.cloud.NativeCloudBridge

    private val operations = Mutex()
    private var server: LanHttpServer? = null
    private lateinit var status: TextView
    private lateinit var secret: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val preferences = getSharedPreferences("stt_lan_host_v1", MODE_PRIVATE)
        secret = preferences.getString("secret", null) ?: randomSecret().also {
            preferences.edit().putString("secret", it).apply()
        }
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 36, 26, 36)
        }
        scroll.addView(root); setContentView(scroll)
        fun label(text: String, size: Float = 16f): TextView {
            val view = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
            root.addView(view); return view
        }
        fun button(title: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } })
        }
        label("STT 手机 ↔ Watch 5 · Wi-Fi直连", 21f)
        label("无需云服务器；手机接收手表独立产生的完整计时记录并自动入库。仅限可信 Wi-Fi，通信未加密；使用中请保持本页面打开。")
        label("配对密钥（手机/手表相同）：\n$secret")
        status = label("未启动")
        button("停止直连服务") { stopServer() }
        val addresses = Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap {
            Collections.list(it.inetAddresses)
        }.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.filter {
            runCatching { LanConnection.checkIp(it.hostAddress); true }.getOrDefault(false)
        }.distinctBy { it.hostAddress }
        for (address in addresses) button("启动服务：${address.hostAddress}:8765") { startServer(address) }
        if (addresses.isEmpty()) status.text = "没有发现私有 IPv4：先连接 Wi-Fi 或手机热点，再重新打开"
        label("手表：打开 Wi-Fi直连设置，填上述 IP 与密钥。手表本地独立计时，完成后自动上传记录到手机原版历史和统计。离开本页面将停止接收，手表保留离线记录等待重连。")
    }

    private fun startServer(ip: InetAddress) {
        stopServer()
        try {
            server = LanHttpServer(ip, secret, ::route)
            status.text = "正在监听 ${ip.hostAddress}:8765\n手表连接此地址"
        } catch (e: Exception) {
            status.text = "启动失败：${e.message}"
        }
    }
    private fun stopServer() {
        server?.close(); server = null
        if (::status.isInitialized) status.text = "服务已停止"
    }
    override fun onStop() { stopServer(); super.onStop() }

    private fun route(method: String, path: String, data: JSONObject?): Pair<Int, JSONObject> = runBlocking {
        operations.withLock {
            when {
                method == "GET" && path == "/v1/state" -> 200 to state()
                method == "POST" && path == "/v1/import" && data != null -> importWatchRecord(data)
                else -> 404 to error("unknown_route")
            }
        }
    }
    private fun error(code: String): JSONObject = JSONObject().put("protocol", 1).put("error", code)
    private suspend fun state(): JSONObject {
        val activityArray = JSONArray()
        for (type in types.getAll()) {
            activityArray.put(JSONObject().put("id", type.id).put("name", type.name)
                .put("icon", type.icon).put("hidden", type.hidden))
        }
        return JSONObject().put("protocol", 1).put("activities", activityArray)
    }

    /**
     * Watch owns timer start/stop timestamps. Phone only imports a completed
     * watch session after validating and committing it in the native Room DB.
     * Stable record IDs make lost HTTP replies safe to retry without duplicates.
     */
    private suspend fun importWatchRecord(data: JSONObject): Pair<Int, JSONObject> {
        val sessionUuid = try {
            java.util.UUID.fromString(data.getString("record_id"))
        } catch (_: Exception) {
            return 400 to error("invalid_record_id")
        }
        if (sessionUuid.version() != 4) return 400 to error("invalid_record_id")
        val typeId = data.optLong("activity_id", -1L)
        val start = data.optLong("started_at", -1L)
        val end = data.optLong("ended_at", -1L)
        val comment = data.optString("comment", "")
        val now = System.currentTimeMillis()
        if (typeId <= 0 || types.get(typeId) == null) return 409 to error("activity_missing")
        if (start < 946684800000L || end < start || end > now + 60000L ||
            comment.length > 4096 || end - start > 365L * 24 * 3600 * 1000) {
            return 400 to error("invalid_record")
        }
        val recordId = (sessionUuid.mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1_000_000_000L
        val previous = records.get(recordId)
        if (previous != null) {
            if (previous.typeId != typeId || previous.timeStarted != start ||
                previous.timeEnded != end || previous.comment != comment) {
                return 409 to error("record_id_conflict")
            }
            return 200 to JSONObject().put("protocol", 1).put("accepted", true)
                .put("record_id", sessionUuid.toString())
        }
        records.add(Record(recordId, typeId, start, end, comment, emptyList()))
        updates.send()
        cloudBridge.notifyLocalChange()
        return 200 to JSONObject().put("protocol", 1).put("accepted", true)
            .put("record_id", sessionUuid.toString())
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(16); SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Bounded HTTP/1.1 server, bound to the selected private IPv4 only. */
private class LanHttpServer(
    address: InetAddress,
    private val token: String,
    private val route: (String, String, JSONObject?) -> Pair<Int, JSONObject>
) : AutoCloseable {
    private val socket = ServerSocket()
    private val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    @Volatile private var closed = false
    init {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, LanConnection.PORT), 4)
        Thread({ acceptLoop() }, "stt-lan-accept").apply { isDaemon = true }.start()
    }
    private fun acceptLoop() {
        while (!closed) {
            try {
                val client = socket.accept()
                try { pool.execute { handle(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { client.close() }
            } catch (_: Exception) { if (!closed) close() }
        }
    }
    private fun handle(client: Socket) {
        client.use {
            try {
                client.soTimeout = 4000
                val input = client.getInputStream()
                val head = ByteArrayOutputStream()
                var tail = 0
                while (head.size() < 4096 && tail != 0x0d0a0d0a) {
                    val b = input.read(); if (b < 0) throw IllegalArgumentException("incomplete_header")
                    head.write(b); tail = (tail shl 8) or b
                }
                if (tail != 0x0d0a0d0a) throw IllegalArgumentException("header_too_large")
                val lines = head.toString("US-ASCII").split("\r\n")
                val request = lines.first().split(" ")
                if (request.size != 3 || request[2] != "HTTP/1.1") throw IllegalArgumentException("invalid_request")
                val headers = mutableMapOf<String,String>()
                for (line in lines.drop(1).filter { it.isNotEmpty() }) {
                    val idx = line.indexOf(':')
                    if (idx <= 0) throw IllegalArgumentException("invalid_header")
                    val name = line.substring(0, idx).lowercase(java.util.Locale.ROOT)
                    if (!name.matches(Regex("[a-z0-9-]+")) || headers.put(name, line.substring(idx+1).trim()) != null)
                        throw IllegalArgumentException("duplicate_header")
                }
                if (!MessageDigest.isEqual(("Bearer "+token).toByteArray(StandardCharsets.US_ASCII),
                        headers.getOrDefault("authorization", "").toByteArray(StandardCharsets.US_ASCII))) {
                    respond(client,401,JSONObject().put("protocol",1).put("error","unauthorized"));return
                }
                if (headers.containsKey("transfer-encoding")) throw IllegalArgumentException("unsupported_encoding")
                val method=request[0];val path=request[1]
                val data = if (method=="POST") {
                    if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") throw IllegalArgumentException("json_required")
                    val length=headers["content-length"]?.toIntOrNull() ?: throw IllegalArgumentException("length_required")
                    if (length !in 1..4096) throw IllegalArgumentException("invalid_length")
                    val body=ByteArray(length);var got=0
                    while (got<length) {
                        val n=input.read(body,got,length-got)
                        if (n<0) throw IllegalArgumentException("incomplete_body")
                        got+=n
                    }
                    JSONObject(String(body,StandardCharsets.UTF_8))
                } else {
                    if (headers.getOrDefault("content-length","0")!="0") throw IllegalArgumentException("unexpected_body")
                    null
                }
                val (code,response)=route(method,path,data)
                respond(client,code,response)
            } catch (e: Exception) {
                try { respond(client,400,JSONObject().put("protocol",1).put("error","bad_request")) }
                catch (_: Exception) { /* client gone */ }
            }
        }
    }
    private fun respond(client: Socket, code: Int, json: JSONObject) {
        val body=json.toString().toByteArray(StandardCharsets.UTF_8)
        val reason=when(code){200->"OK";400->"Bad Request";401->"Unauthorized";404->"Not Found";409->"Conflict";else->"Error"}
        val header="HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n"
        client.getOutputStream().apply { write(header.toByteArray(StandardCharsets.US_ASCII));write(body);flush() }
    }
    override fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }
}
