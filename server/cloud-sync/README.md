# STT 云端计时：先部署服务器，再安装同一个 APK 到手机和手表

本目录属于 `DoubleCore/Android-SimpleTimeTracker` 的 v1.59 实验分支。
2026-10-21 用户反馈 M0 连通性已成功；本阶段改为手机和手表共同连接服务器。

## 这一版能做什么

- 在任一端创建活动，两端同步活动列表。
- 任一端开始/停止计时，另一端前台页面自动刷新，正常网络下轮询间隔 2 秒（不是硬性延迟保证）。
- 服务器 SQLite 持久保存活动、计时区间、操作回执与增量事件；写事务提交后才 ACK。
- 本机 SQLite 先保存操作 ID、设备 ID、事件时间、目标计时 ID。断网/退出/进程重启后保留，重新打开自动补传。
- 重试保持同一个操作 ID；并发重复请求只产生一个结果。过期停止命令不会停止后来的计时。
- 服务端事件按游标分页；客户端数据和游标同一事务提交，不依赖手机在线。
- 认证失败不删除队列；冲突显示为“需处理”，由用户核对，不伪装成成功。

**使用边界：**这是可以记录真实计时数据的新“STT 云同步”入口，并非网络探针。
当前云端数据与原版 STT Room 历史、标签、统计、原版计时按钮相互独立；没有自动迁移或双写旧历史。
本轮优先保证云端入口的双端闭环。使用云同步时，手机和手表都从“STT 云同步”进入。
后台页面关闭后不承诺持续上传；已接受的计时在服务器继续存在，并不需要持续发送“心跳计时”。
所有时间按设备操作时的 UTC Unix 毫秒保存，请让两台设备自动校时。
首版是单用户/一个工作区，两端共用一个高熵密钥；不是多用户 SaaS。

## 最快部署（已有 Linux 服务器、Docker Compose、指向服务器的域名）

```bash
git clone --branch feature/xiaomi-watch5-lan-v159 https://github.com/DoubleCore/Android-SimpleTimeTracker.git
cd Android-SimpleTimeTracker/server/cloud-sync
bash setup.sh
```

输入域名，例如 `time.example.com`（不含 `https://`）。脚本生成 `.env`，首次生成 256 位随机密钥。
DNS A/AAAA 必须指向此服务器；若没有可用 IPv6 不要设置错误 AAAA。允许入站 TCP 80/443。
Caddy 自动申请 HTTPS 证书并反向代理；API 的 8765 端口不映射到公网。
SQLite 保存在 Docker named volume `stt-data`，重启/更新容器不丢失。
**不要运行 `docker compose down -v`，它会删除数据库卷。**

在服务器本地查看 `.env`，将 `https://域名` 和 `STT_TOKEN` 分别填入两台设备。
不要把密钥提交到 Git 或发到公开 issue。APK 不接受明文 HTTP、自签名/无效证书或携带密钥的重定向。
如已有 HTTPS 反向代理，可只部署 api 服务并让现有代理在私有 Docker 网络访问 api:8765；保持请求路径 `/v1/...`。

检查服务：

```bash
docker compose ps
docker compose logs --tail=50 api caddy
```

尚未在用户服务器上部署。GitHub Actions 是构建平台，不是长期运行此服务的服务器。

## 安装和立即使用

GitHub 仓库 Actions → **Cloud sync** → 通过的构建 → 下载 `stt-cloud-<commit>`。
包内 `stt-cloud-debug.apk` 在手机和 Watch 5 安装同一个文件，无 GMS 依赖，独立包名 `org.doublecore.stt.cloud`。
M0 探针不被覆盖。debug APK 适合当前自用验证；后续公开发布需固定发布签名和密钥管理。

1. 手机和手表打开“STT 云同步”，分别填写相同 HTTPS 地址和服务器密钥，保存并连接。
2. 手机创建“学习”；手表页面打开后会看到它。
3. 手表点“开始”，手机同步显示计时中；任一端点“停止”，两端显示已保存的时间区间。
4. 手机可以完全离线，手表仍能独立向服务器保存操作。
5. 手表断网后开始、停止，界面明确显示“待同步”；联网并重新打开后自动补传。

没有默认服务器地址，也没有把个人密钥编入 APK。配置保存在 app 私有 no-backup 目录。
圆屏使用纵向滚动和较大上下留白，初次配置需要滚动；还需真机确认输入体验。
修改到另一服务器前必须处理所有待同步/失败操作；不同服务地址使用不同本地数据库。
若服务器数据库被替换，工作区 ID 不同会阻止混入原缓存。恢复较旧备份导致客户端游标超前时也会报错，不能静默跳过数据。

## 仓库如何接入现有工程

- `cloud_sync/`：共享 Android 库，包含 CloudActivity、CloudStore、CloudSync、CloudHttp、CloudConfig。
- `app/build.gradle.kts`、`wear/build.gradle.kts`：均依赖该模块，合并 Manifest 后新增“STT 云同步”启动入口。
- `tools/cloud-app/`：使用同一共享模块的轻量通用 APK；Watch 5 优先安装此 APK，不初始化旧 Wear 应用的 Google 组件。
- `server/cloud-sync/`：服务器与部署配置。
- `.github/workflows/cloud-sync.yml`：服务端测试、APK/lint、Android 存储测试、原手机/Wear 构建。

原手机/Wear 集成包保留已有启动流程；云同步的网络本身不依赖 Google。
轻量 APK 不会读取其他包的私有数据库。原 App 的云入口也使用独立数据库，没有声称已同步旧记录。
不要混用轻量包和原集成包的本地队列；它们是不同包名。连接同一服务器后会同步服务器已接受的数据。

## API v1

所有请求：`Authorization: Bearer <STT_TOKEN>`，生产只允许 HTTPS。

| 接口 | 说明 |
| --- | --- |
| GET /v1/health | 协议版本、工作区 ID；也需要认证 |
| POST /v1/operations | 一条 activity/start/stop 操作；JSON 最大 8 KiB |
| GET /v1/sync?after=0 | 按 seq 递增返回最多 200 条事件；has_more 为 true 时继续 |

公共操作字段：`id`、`device_id`（规范 UUID 字符串）、`kind`、`occurred_at`（整数毫秒）。
- activity：再含 `activity_id`（UUID）、`name`（1～80 字符）。
- start：再含 `activity_id`、`session_id`（均 UUID）。
- stop：再含 `session_id`（必须指向原开始计时），不按“当前活动”盲目停止。

200 ACK 含 `accepted: true`、`operation_id`、`workspace_id`、`protocol: 1`。只有 SQLite 事务提交后返回。
同 ID/同请求返回原 ACK；同 ID/不同请求为 409。领域冲突也保存回执，重试不能改变结果。
多活动可并行，同一个活动同时最多一个运行记录。并发冲突按服务端接收事务顺序判定；离线冲突不自动覆盖。
同步事件为 `seq`、`entity`（activity/session）、`data`，结束记录为同一 session 的更新。
本机应用顺序严格检查，cursor 与实体更新一同提交。所有历史保存在数据库，界面仅展示最近 50 条。

## 验证

```bash
python3 -m unittest discover -s server/cloud-sync/tests -v
bash ./gradlew -p tools/cloud-app :app:assembleDebug :app:lintDebug :cloud_sync:lintDebug
bash ./gradlew -p tools/cloud-app :app:connectedDebugAndroidTest
bash ./gradlew :app:assembleBaseDebug :wear:assembleDebug
```

服务端测试覆盖真实 HTTP 双端读写、重启保留、丢 ACK 重试、并发重复写/开始冲突、过期停止、离线事件时间、分页、认证与事务回滚。
Android 仪器测试覆盖队列重开持久性、离线开始停止投影、游标/数据原子性、工作区隔离、失败操作处理和 HTTPS 地址校验。
CI/真机状态以本次提交对应 Actions 为准，不把代码存在等同于构建成功。

真机验收：
- [ ] 两端不同网络，创建→开始→停止→相同区间。
- [ ] 手机关网后，手表仍可以保存；手机恢复后获取。
- [ ] 手表离线开始和停止，杀进程再重开仍见队列，恢复后只有一条完成记录。
- [ ] 重启服务器后历史保留，重复上传不重复计时。
- [ ] 错误密钥显示认证失败且队列不丢失。
- [ ] 同活动两端同时开始，失败端明确显示冲突并能继续使用。

## 备份、升级、回退

在线备份使用 SQLite backup API，避免直接只复制 WAL 模式下的主数据库：

```bash
docker compose exec api python -c 'import sqlite3; src=sqlite3.connect("/data/stt.sqlite3"); dst=sqlite3.connect("/data/backup.sqlite3"); src.backup(dst); dst.close(); src.close()'
docker compose cp api:/data/backup.sqlite3 ./backup.sqlite3
```

将备份保存到服务器以外。备份有个人记录，不要提交到仓库。升级前备份，再 `git pull` 与 `docker compose up -d --build`。
回退代码不删除数据库；本版 schema 尚无破坏性迁移。不要清除 App 数据或卸载含未同步队列的 APK。
更换密钥会使旧密钥失效，两端都要更新设置；更换数据库/恢复旧快照需要单独恢复流程，不清空本地数据来掩盖异常。

后续：接通旧 STT 活动/历史映射、后台调度、便捷配对、导出与账户管理；不阻塞本版云端计时闭环。
