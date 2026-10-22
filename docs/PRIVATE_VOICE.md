# 私人预配置构建与语音描述

此变更尚未完成 Android 编译或真机验证，不是已交付的新 APK。

## 私人预配置

具备项目需要的 JDK 21、Android SDK 和 Gradle 环境后，在仓库根目录运行：

```bash
bash tools/build-private-apks.sh
```

脚本交互读取 HTTPS URL 和隐藏输入的 STT_TOKEN，在仓库外临时生成资产文件，构建手机及手表 APK，输出到已忽略的 private-delivery/。不允许在 GitHub Actions 运行。源码不包含密钥。安装包及本机构建中间文件会包含可提取的密钥，必须按私人文件保管，不能公开发布。

应用仅在没有已保存连接配置时读取预设；已有设备不会被静默换地址。预配置不等于服务端已升级：原生手机记录同步仍要求 native_records_v1。

## 语音描述

手机正在计时和历史记录所共用的描述编辑框增加语音入口。调用系统 ACTION_RECOGNIZE_SPEECH，结果可编辑、确认后追加，取消不改原文。页面或原文已变化时拒绝覆盖。

系统识别服务可能在线处理音频，启动前会说明。设备没有提供该接口时保留键盘输入并显示原因。此实现不包含云端语音识别，也没有验证国行 Watch 5 的系统识别能力；手表描述编辑和语音入口尚未实现。

官方接口依据：https://developer.android.com/reference/android/speech/RecognizerIntent
