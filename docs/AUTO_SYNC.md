# TimeTrak 自动同步：手机 / Watch 5 / 云端

> 本分支以 Wi-Fi 直连 + 手机已有 HTTPS 云同步为基础，追加自动触发和后台重试。
> 不需要日常手动点击“立即同步”。M0 联通用户已确认；本分支新后台功能需独立真机验证。

## 路径 A：Watch 5 局域网直连（首选）

`Watch 5 点开始/停止` → 手机热点/同 Wi-Fi → 手机 LAN HTTP → **手机原版 Room DB** → 手机 NativeCloudBridge → HTTPS 云服务器。

- 手机收到写入动作后主动唤醒 `NativeCloudBridge`；手机原版计时、历史和统计继续共用同一数据源。
- 手机云同步在应用进程运行时仍每约 2 秒扫描本地新数据，也能被事件立即唤醒。
- 原版 STT 的 Activity 只负责手机 LAN 服务的**前台生命周期**。若手机关闭直连页面，手表无法继续通过该端口提交操作；后台 **云上传重试** 与局域网服务保活是不同需求。
- **重要限制：Watch LAN 模式目前不会把未发出成功回执的指令自动变成新的离线队列；不能将当前 LAN 实现描述为“手表离线计时后自动补传”。** LAN 请求失败时界面返回错误，避免误认作已保存。

## 路径 B：手表独立 HTTPS 云模式

`Watch 5 点开始/停止` → 先写手表 CloudStore SQLite outbox → HTTPS 云服务器 → 手机拉取增量并写入原版 Room。

- 手表已有待同步操作按原 `CloudSync` 自动上传、以服务器 ACK 删除。
- 手表在活动进程运行时约每 2 秒尝试同步；发生新的云模式操作时额外排入系统一次性补传任务。
- 新增非 GMS Android JobScheduler 每 15 分钟的周期恢复任务，注册为可重启后恢复（需要 `RECEIVE_BOOT_COMPLETED`）。
- 若手表既配置 Wi-Fi 直连又配置云端，前台活动状态优先读取手机；原有独立云 outbox 仍可由同步循环/后台任务补传。

## 手机云上传与后台

- 启动应用自动启动 NativeCloudBridge；对本地记录变化会加速触发扫描。
- 每次 LAN 操作成功写入原版数据，立即触发云同步并安排一次性后台恢复任务。
- 手机安排 JobScheduler 网络约束的 15 分钟周期任务，无需用户打开上传页面；设备重启后由 Android 系统负责恢复持久化任务。
- 不会在云端没有有效配置时上传私人计时数据。
- 所有云通信采用现有 HTTPS 和 Bearer token，云端真正提交后才视为云上传完成。Wi-Fi 直连是单独的可信局域网 HTTP 通道，不能映射公网。

## Android 系统限制

- JobScheduler/WorkManager 后台周期任务最短 15 分钟，具体执行时间受 Doze、电量优化、设备厂商后台管理、联网条件影响，**不是每 15 分钟精确执行**。
- 用户手动强行停止应用、设备极端省电、后台限制时，不能保证任务立即运行；解限、联网、下次打开应用后应重新扫描并补传。
- 本分支没有新增“常驻前台服务”，也没有声称锁屏后的 LAN 直连永久在线；若要求数秒级的 24h 后台互联，需另外设计和验证符合 Android 最新限制的前台服务（含通知、权限、运行时限）。

## 验收清单

1. 手机、手表新 APK 都能正常启动；无 Google Play Services 的 Watch 5 可安装。
2. 手机局域网接收手表开始/停止后，原版 Room 历史出现记录，**无需点上传按钮**；手机云端 outbox 随后清空，云端仅有一份相同 ID。
3. 手机原版计时按钮开始/停止后，自动触发云端上传，不需要进入云同步页面。
4. 手机断网时，本地 Room/云 outbox 保留；联网后后台调度或重新打开应用可补传。
5. 手表独立云模式离线起停后，SQLite outbox 保存操作；联网后台调度自动发送并服务器 ACK。
6. 退出两端界面、手机/手表重启后，周期任务仍已注册；其实际开始时间不得冒充精确 15 分钟 SLA。
7. 错误 token、无网络、服务器 5xx、丢 ACK 下无静默丢记录或重复记录。
8. 手表 LAN 模式断联必须提示失败，不能谎称离线 LAN 操作已经成功入队。

参考 Android 官方：<https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work> 和 <https://developer.android.com/develop/background-work/background-tasks/persistent>。

## 代码入口

- 手机自动同步：`app/src/main/java/com/example/util/simpletimetracker/cloud/NativeCloudBridge.kt`
- 手机后台重试：`app/src/main/java/com/example/util/simpletimetracker/cloud/PhoneCloudRetryJobService.kt`
- 手表云队列：`wear/src/main/java/com/example/util/simpletimetracker/data/WearDataRepo.kt`
- 手表后台重试：`wear/src/main/java/com/example/util/simpletimetracker/data/WatchCloudRetryJobService.kt`
- 共享同步协议：`cloud_sync/src/main/java/org/doublecore/stt/cloud/CloudSync.java`

只需初次配置手机和手表的网络/密钥，后续上传自动运行；当前云服务器必须提供兼容 API（`native_records_v1`）并真实在线。
