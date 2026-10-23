# TimeTrak：手机直接联网大模型 API 自动分类（BYOK）

## 数据流（AI 不经过 Railway）

Watch 5 本地独立记录、语音描述 → Wi-Fi 将完整记录上传手机 → 手机原生 STT Room → **手机 HTTPS 直连购买的大模型 API** → 从 STT 已有标签中选 ID → 手机原生 Room 保存标签。

Railway/TimeTrak 云同步仍是**另一个独立通道**，不负责 AI 推理、不代理模型 API、不持有模型 API Key。

## 手机配置

手机 TimeTrak 首页点击「AI自动分类 · 购买的模型API」。
1. 填 API 完整地址（HTTPS、结尾为 `/chat/completions`，例如 `https://api.openai.com/v1/chat/completions`；这里仅为路径格式示例，并不限定服务商）。
2. 填购买平台提供的实际模型 ID。
3. 填实际购买的 API Key。
4. 点「启用手机自动分类」。

首次启用前的历史记录被标记为基线，**不会自动批量发送历史描述并产生费用**。以后新导入/新完成且有 Description 的记录会按本地记录更新通知或系统后台任务尝试分类。

当前实现面向 **OpenAI Chat Completions 风格** 的兼容 API；不是所有商业模型供应商都兼容。模型输出被要求为 JSON `{"tag_ids":[1,2],"confidence":0.9}`，不受信任，解析失败或低置信度不写标签。

## 现有 STT 标签策略

- 调用 `GetSelectableTagsInteractor.execute(record.typeId)`，只向 API 发送该活动可选的已有标签列表。
- 排除已归档和 NUMERIC 数值型标签；只选择普通标签，最多 3 个。
- 模型给出的 tagId 必须在此次传送的白名单之内，否则本次不应用。
- 模型自报 confidence 低于 0.85 或解析失败时不写标签；注意**模型自报 confidence 并非统计校准的可靠概率**。
- 新标签采取合并（union），**保留已有的手动标签**。AI 没有权限创建、删除、重命名标签。
- 请求发出后，如描述、活动或时间被用户编辑，丢弃过期结果，避免覆盖正在编辑的记录。
- 用户以后修改描述，可以触发重新分析；不会自动移除已经存在的标签，防止误删手动标签。
- 首版没有实现 AI 标签冲突可视化审核、自动移除旧 AI 标签或数字标签赋值。

## 密钥、安全和费用

- AI Key 使用 Android Keystore AES-GCM 加密，写入手机 `noBackupFilesDir`，不进 APK、GitHub、Railway、URL 或日志。
- 手机直接连第三方模型提供商。对方会收到用于分类的 Description（最多 2500 字符）和当前活动允许的标签名称/备注（最多 200 项），不发送原始音频或整本时间账本。
- 用户主动开启才执行分类；关闭设置会清除本地已保存的加密凭据文件，不会回滚已分类记录。
- 只允许 HTTPS、使用 Android 标准服务端证书校验，拒绝 HTTP 重定向以免泄露 Key。
- API 调用可能计费。每次内存扫描最多处理 3 条记录，最短 60 秒间隔发起下一轮，后台调度受 Android 网络/省电策略限制。
- 手机断网、购买模型服务不可用、缺少可选标签时，计时和原有记录保存不受影响；后续重试不会阻塞手表本地计时。
- **云端标签同步尚不完整：** 目前原有 `NativeCloudBridge` 的会话快照只包含活动、时间和 Description，不包含 RecordBase.Tag。AI 标签目前写入手机原版 Room，不能声称跨设备/服务器备份恢复标签已经完成。

## 主要源码

- `app/src/main/java/com/example/util/simpletimetracker/ai/PhoneAiConfig.kt` — 本机 Key 管理、HTTPS 约束。
- `app/src/main/java/com/example/util/simpletimetracker/ai/PhoneAiTagger.kt` — 记录变化监听、标签白名单、直接访问 API、写回 Room。
- `app/src/main/java/com/example/util/simpletimetracker/ai/PhoneAiSettingsActivity.kt` — 手机设置入口。
- `app/src/main/java/com/example/util/simpletimetracker/cloud/PhoneCloudRetryJobService.kt` — 后台系统重试，与 Railway 云同步任务分离。

## 验收

- [ ] 首次启用已有 100 条旧记录，不产生 100 次 API 调用。
- [ ] 手机不配置 Railway 也可在联网时对新带描述记录进行 AI 标签分类。
- [ ] Watch 导入新记录，手机自动调用模型，返回已存在且可选的标签 ID 后写入 Room。
- [ ] AI 返回陌生 tagId、超出 3 个标签、非 JSON、低置信度时，原记录标签不被替换。
- [ ] 手机人工修改标签或描述，旧模型请求不得覆盖最新描述或删除人工标签。
- [ ] HTTP 401/429/500、网络断开、手机后台进程终止时，不丢记录且不产生虚假的成功状态。
- [ ] 软件或系统重启后 Key 能被正常解密，上传仍是 HTTPS；断网后标签任务可再次尝试。
- [ ] API Key 不出现在 GitHub、APK 可提取的静态资源和 HTTP query string 中。
- [ ] 云端恢复标签功能需在扩充 cloud schema 后单独验收。

CI 只能确认可编译；真实 AI 分类必须使用用户自己的合法 API 地址/模型/Key 进行设备验收。
