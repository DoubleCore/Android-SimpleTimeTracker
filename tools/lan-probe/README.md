# Watch 5 国行 · M0 局域网连通性探针

这是实施计划的 **PR-01 / PR-02 起点**，不是改造后的 Simple Time Tracker。
用同一个独立 APK，在手机开启测试 HTTP 服务，在手表发送并验证请求。
测试通过后，才进入 `WearMessenger → HTTP → WearRPCServer` 的 M1 改造。

## 代码与范围

- STT 基线：v1.59，`ec82d9afa4c26f03f0a73eb7deac2d3ddf73254b`。
- 实验分支：`feature/xiaomi-watch5-lan-v159`。默认 `dev` 不作为本轮基线。
- 包名：`org.doublecore.stt.lanprobe`；不会覆盖原手机或手表 STT，也不访问其数据库。
- 独立 Gradle 项目，不加入原工程 settings，不更改原版 base/play/wear 构建。
- 纯 Android Activity + Java socket / HttpURLConnection；无 GMS、Wearable 共享库、Compose、Hilt 或第三方运行依赖。
- 仅支持私有 IPv4、固定端口 8765；只绑定手机用户选中的一个地址，不监听所有网卡。
- 只有带临时随机令牌的 `GET /health?nonce=<32位hex>`，响应必须精确匹配挑战值。
- 不支持 `/stt/*`、计时操作、发现、离线队列、后台保活或公网访问。
- 服务与客户端随 Activity `onStop` 停止；测试期间保持屏幕点亮；每次重新创建页面生成新令牌。

## 取得 APK

优先使用本仓库 Actions 的 **LAN M0 probe** 工作流：

1. 进入对应实验分支的工作流运行记录。
2. 确认构建任务成功，下载 `stt-lan-m0-debug-<commit>` artifact。
3. 解压得到 `stt-lan-m0-debug.apk`、`COMMIT.txt`、`SHA256SUMS.txt`。
4. 手机和手表安装**同一份** APK；核对提交号，不混用不同运行记录的产物。

源码存在不代表 APK 已构建；以该运行的状态和产物为准。探针是 debug 签名，不能作为正式发布包。

本地构建要求：JDK 17、Android SDK Platform 34 / Build Tools 34.0.0；Gradle 8.6 与 AGP 8.4.0 与基线匹配。
配置 `ANDROID_HOME`，或者在 `tools/lan-probe/local.properties` 设置 `sdk.dir`（不提交）。

在仓库根目录运行：

```powershell
.\gradlew.bat -p tools/lan-probe :app:assembleDebug :app:lintDebug
```

Linux/macOS：

```bash
bash ./gradlew -p tools/lan-probe :app:assembleDebug :app:lintDebug
bash tools/lan-probe/tests/run.sh
```

本地产物：`tools/lan-probe/app/build/outputs/apk/debug/app-debug.apk`。
不需要编译原 STT 手机与 Wear 模块。

## 第一次真机测试（Windows PowerShell）

1. 手机和手表加入可互访的可信 Wi-Fi；也可先试手机热点。关闭访客网络/AP 隔离。
2. 用 `adb devices -l` 确认两个设备的序列号。下面尖括号必须换成实际值，避免把 APK 装错设备。

```powershell
adb -s <PHONE_SERIAL> install -r .\stt-lan-m0-debug.apk
adb -s <WATCH_SERIAL> install -r .\stt-lan-m0-debug.apk
adb -s <PHONE_SERIAL> shell am start -n org.doublecore.stt.lanprobe/.MainActivity
adb -s <WATCH_SERIAL> shell am start -n org.doublecore.stt.lanprobe/.MainActivity
```

3. 手机选“手机：服务端”，选择与手表同网段或热点接口的 IPv4，点击“启动手机测试服务”。必须出现 `LISTENING <IP>:8765`。
4. 保持手机探针页面打开，在手表选“手表：客户端”，输入手机 IPv4 与页面显示的32位临时令牌，点“连续测试5次”。
5. 预期手表出现五条 `PASS ... nonce matched` 和 `RESULT 5/5`，手机出现对应 `HEALTH_OK peer=<手表IP>`。
6. 记录结果后做错误令牌测试：令牌改一个字符，应显示认证失败，不能 PASS。
7. 停止手机测试服务，再点手表测试，应失败。重新启动服务后应恢复 5/5。
8. 手表断开 Wi-Fi 再测，记录实际结果。若仍成功，检查是否存在蓝牙代理或其他可达链路，不能强行把它归类为失败。重新连接后复测。

小屏输入可通过 ADB 辅助：先点中手表输入框，再执行 `adb -s <WATCH_SERIAL> shell input text <PHONE_IP>`；令牌也可同样输入。
这样输入的令牌可能留在电脑终端历史中，测试结束关闭手机页面使它失效。不要把令牌写入证据文件。
不要使用 `adb forward` / `adb reverse` 来证明 Wi-Fi 可达：那验证的是调试桥隧道。

## 保存证据

为每次测试复制 [M0-device-evidence.md](M0-device-evidence.md) 并填写真实结果。
两端都要有证据；电脑访问手机成功不能代替 Watch 5 的客户端请求。

```powershell
adb -s <PHONE_SERIAL> shell getprop ro.product.model
adb -s <PHONE_SERIAL> shell getprop ro.build.version.release
adb -s <WATCH_SERIAL> shell getprop ro.product.model
adb -s <WATCH_SERIAL> shell getprop ro.build.fingerprint
adb -s <WATCH_SERIAL> shell pm list packages com.google.android.gms
adb -s <PHONE_SERIAL> logcat -d -v threadtime -s SttLanM0:I > phone-m0.txt
adb -s <WATCH_SERIAL> logcat -d -v threadtime -s SttLanM0:I > watch-m0.txt
adb -s <WATCH_SERIAL> logcat -b crash -d -v time > watch-m0-crash.txt
```

对照测试时间区间，排除旧日志。日志包含内网 IP 和设备信息，分享前按需要脱敏。
日志不包含配对令牌。截屏手机页面时必须遮住令牌。

## 如何判定

| 状态 | 结论 / 下一步 |
| --- | --- |
| `RESULT 5/5`，两端日志匹配 | 本次前台 HTTP 连通；完成负例和恢复测试后可评审进入 M1 |
| HTTP 401 / 认证失败 | 已到达服务；重新核对当前手机页面的令牌 |
| 连接失败 | 手机未启动、选错 IP/接口、端口不通，或系统网络限制 |
| 超时 | 查 AP 隔离、热点路由、手机页面是否退出、网络是否切换 |
| PROTOCOL_MISMATCH | 访问到错误服务，或两端不是同版本探针 |
| 手机退出/锁屏后不通 | M0 预期：探针只在可见界面运行；不等于 M3 后台方案失败 |

**M0 通过并不代表计时同步、后台连接或正式安全已完成。**
M1 才接入真实活动查询，M2 才执行 Start/Stop 与状态刷新。

## 安全边界与清理

HTTP 明文仅限可信测试网络；临时128位令牌用于减少误接入，不提供加密或抵御同网段窃听。
不做端口映射，不复用正式凭证。只有健康探针，无活动名、记录或业务写入口。
服务器限制请求头4 KiB、头部读取3秒、2个工作线程与4个排队连接；客户端限制响应4 KiB、拒绝重定向。
这些限制用于 M0 诊断，不应把自制 HTTP 解析器直接迁移为生产业务网关。

停止：点停止或退出页面。卸载只删除本探针：

```powershell
adb -s <PHONE_SERIAL> uninstall org.doublecore.stt.lanprobe
adb -s <WATCH_SERIAL> uninstall org.doublecore.stt.lanprobe
```

## 验证状态（2026-10-20）

- 本地 Java 网络集成测试：**36项断言通过**，包括真实 loopback HTTP、认证、路由白名单、请求/响应大小、拒绝重定向、挑战值检查和关闭端口。
- 本地 Android 构建：**未完成**。SDK未安装；首次 Gradle 下载报 `Network is unreachable`。不能据此断言应用代码编译失败或成功。
- CI：已配置 APK 构建和 lint，实际结果见 Actions 对应提交。
- 手机 / Watch 5 安装、UI、网络、断线与恢复：**待真机验证**。
- 原 STT 1.59 全量构建、数据库回归、M1~M5：**本轮未执行**。

参考：[Android 明文网络配置](https://developer.android.com/privacy-and-security/security-config)、[Activity 生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)。
