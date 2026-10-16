# DiPlay v0.2.69 — 修复 Android 4.2 音乐播放媒体按键注册闪退

## 更新内容

### 1. Android 4.2 / API 17 音乐播放闪退修复
- **适配 API < 18 媒体按键注册接口**：在 Android 4.2（API 17）等低版本系统上，`AudioManager.registerMediaButtonEventReceiver(PendingIntent)` 与 `unregisterMediaButtonEventReceiver(PendingIntent)` 接口尚未引入（API 18 引入），导致在点击音乐播放或进入 Now Playing 时直接抛出 `java.lang.NoSuchMethodError: android.media.AudioManager.registerMediaButtonEventReceiver` 并崩溃闪退 (#197)。
- **版本自适应 ComponentName 注册与注销**：在 API < 18 下自适应切换使用经典 `registerMediaButtonEventReceiver(ComponentName)` 与 `unregisterMediaButtonEventReceiver(ComponentName)`，确保老旧系统顺利接收方控与媒体按键事件。
- **全方位异常捕获与容错**：对 `registerMediaButtonEventReceiver`、`RemoteControlClient` 注册以及 `setPlaybackState` 等旧版音频控制调用增加全面防御性异常捕获，即使在第三方深度定制或缺少相关系统服务的车机 ROM 上，也能确保 CarPlay 音频平稳输出而不崩溃退出。

---

## 验证与发布

- **版本标识**：`versionName 0.2.69` / `versionCode 89`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本完全一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试与构建校验全部通过。
