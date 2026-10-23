# TimeTrak：原版界面冻结与设置入口集中化（2026-10-23 修订）

## 用户确认的优先级

**不做 UI 设计。** 原版 Simple Time Tracker v1.59 的手机首页、计时卡片、历史、统计、记录描述页面、手表首页、计时交互和导航保持原样。新功能的配置只放在现有设置列表下。

## 已调整代码

- `app/src/main/res/layout/main_activity.xml`、`app/.../ui/MainActivity.kt`：逐字恢复 v1.59 原版内容，撤回此前添加的主页面云同步横幅。
- `features/feature_settings/src/main/res/layout/settings_fragment.xml`：逐字恢复 v1.59 原版 RecyclerView 布局，删除 PR #7 自行添加的顶部大按钮。
- 原版设置模块的 `SettingsMainViewDataInteractor` 新增 **一个标准 SettingsTextViewData 列表项**，交由 `SettingsFragment` 打开 `CloudSettingsActivity`；不改动现有设置行布局、资源及导航逻辑。
- Wear 设置页也移除独立顶层 `CompactChip` 和外层 `Column`，改为使用原生 `SettingsList` 添加一个标准文字操作项；看起来与已有设置行相同，不改变其他行。
- `wear/.../presentation/MainActivity.kt` 恢复原版 `onCreate`，撤回首次启动自动弹出云连接设置。
- `feature_base_adapter/.../ChangeRecordCommentFieldAdapterDelegate.kt` 恢复原版外观，撤回额外麦克风图标。旧的语音识别实现文件保留但本版没有 UI 入口。
- `NativeCloudBridge.kt`、`WearDataRepo.kt`、`CloudActivity.java` 恢复 PR #7 之前版本，撤回自动插入 12 个活动、8 个标签及单独云计时页面 UI 改动。
- 保留 `FixedActivities.java` 作为未来功能使用的**静态中文活动常量**；本版不自动向 Room/云队列插入，不重新排列用户已有按钮。它的单元/仪器测试只验证主动调用时不会重复，不能理解成上线会自动应用。
- `CloudSettingsActivity.java` 已恢复至改动前的云同步设置页面：只保留原有 URL/密钥/同步详情，不自行增设 AI、语音或 LAN 占位布局。未实现的功能不显示假设置。

## 验收边界

允许的可见变更仅为：
1. 手机原版「设置」列表中新增一条与原生样式一致的「TimeTrak · 云同步与功能设置」。
2. 手表原版「设置」列表新增一条「云同步连接设置」。
3. 进入上述入口后显示云同步与新增功能配置子页面。

不能出现：主界面顶部横幅、新设计活动卡片、自动增加 12 张活动卡、替换原版记录编辑框、自行设计计时页/统计页、首次启动强制跳转设置。

## 验证

静态文件比对目标：`app/main_activity.xml` 与 v1.59 原版字节一致；`SettingsFragment` 的 XML 与原版一致；原版手机 `MainActivity.kt` 不变；手表 `MainActivity.kt` 与原版一致；记录描述适配器与原版一致。

实际 APK 编译、旧数据迁移/兼容、手表真机交互仍以本分支后续 CI 及真机运行结果为准，不应声称未经执行的结果。
