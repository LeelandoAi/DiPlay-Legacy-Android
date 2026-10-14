# DiPlay v0.2.59 — 老款车机有线 CarPlay 与诊断兼容修复

发布日期：2026-10-14。此版本包含相对于 v0.2.58 的以下更新。

## 更新内容

- **老款车机系统兼容**：USB、VPN、蓝牙、音视频、设置和更新安装等路径改用实际 Android API 等级选择实现，避免部分 T3 车机虚报 SDK 后调用系统中不存在的方法。
- **有线 AirPlay 连接**：修正 IPv6 链路本地地址的接口 scope 查找，增加 TUN 接口回退，并补齐 mDNS 服务枚举应答，改善 iPhone 已发出 TCP SYN、车机监听端无法匹配的场景。
- **有线连接重试**：复用仍可使用的 VPN TUN，重新建立 NCM 桥接、监听和服务发现，减少 Android 4.4 重复建立 VPN 时遗留 fwmark 规则导致的失败；补齐控制器对复用状态的处理。
- **Android 4.4 视频解密**：系统缺少 ChaCha20-Poly1305 时尝试已打包的 Conscrypt 原生实现，并保留原有回退路径，减少纯 Java 帧解密开销。
- **安装与设置**：补齐 Android 4.2/4.4 的旧版 APK 安装入口及外部目录回退，修复设置面板重建时重复移除父容器造成的崩溃。
- **诊断报告**：查看页新增“复制全部”按钮，避免旧车机长按文本选择时闪退；崩溃堆栈逐行脱敏保存，提高排查信息的完整性。
- **Release 构建**：修复跨模块兼容方法的可见性及有线控制状态分支编译问题。

## 安装包

| 项目 | 值 |
| --- | --- |
| 包名 | `com.shihab.diplay` |
| versionName / versionCode | `0.2.59` / `79` |
| 最低 Android 版本 | Android 4.2 / API 17 |
| target SDK | API 37 |
| CPU 架构 | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |
| 文件 | `DiPlay-v0.2.59-legacy-release.apk` |
| 文件大小 | 16,728,181 字节 |
| APK SHA-256 | `37c9a868bfb1eb2c12f77fb106b4b7a4d8e565532a97d514c8c618139f2667fb` |
| 签名证书 SHA-256 | `bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae` |
| APK 对应源码 | `a73dfa028ae41b35f18ee99d24c89fc10f5df0ae`（`v0.2.59` 标签） |

下载 [正式 APK](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/download/v0.2.59/DiPlay-v0.2.59-legacy-release.apk) 和 [SHA-256 校验文件](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/download/v0.2.59/DiPlay-v0.2.59-legacy-release.apk.sha256)。

APK 使用与 v0.2.21 一致的原始正式签名，已通过最低 API 17 的签名校验，包含 v1 和 v2 签名。已安装同包名、同签名版本的用户可使用系统安装器覆盖升级；签名不同的上游版本或历史 Debug 签名版本不能直接覆盖安装。更新前请停车并断开 CarPlay。

## 验证范围

- 已核对本地 APK 的包名、版本、最低 API、CPU 架构、文件大小、SHA-256 和正式签名。
- 已确认 APK 内的两项离线认证资源与本次选择的本地构建输入逐字节一致。
- 离线认证探针通过：两次启动、共六次本地挑战签名校验，修改挑战后签名被正确拒绝，网络请求数为零。
- GitHub 附件重新上传后，已重新下载并确认 APK 字节、SHA-256 文件和本地正式包一致。原附件曾返回 HTTP 404，早先发布校验还在 APK 上传完成前启动，因此失败记录不代表当前附件状态。
- GitHub Actions 最新重测通过：[APK 版本与 SHA-256 校验](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/actions/runs/38556241339)、[历史正式签名校验](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/actions/runs/38556247513)。
- Home 的 4 项单元测试通过；`mobile`、`home`、`maphost` 的 Debug Lint 和 Debug 构建全部通过，`mobile:lintRelease` 通过。
- 已执行项目要求的九项 CI 检查命令。`shared` 的 1,142 项测试通过，`common` 的 924 项测试中 4 项失败、2 项跳过，因此完整 CI **未通过**。失败项如下：
  - `AdaptiveSettingsUiTest.selectingTheBottomRailDestinationKeepsItVisibleAndFocusedOnAShortScreen`：短屏底部导航可见性/焦点断言。
  - `BydVehicleDataSettingsTest.samePageRenderKeepsScrollAndPageChangeStartsAtTop`：同页重建后的滚动位置断言。
  - `BydVehicleDataSettingsTest.rendersBeforeTheNextLayoutKeepTheScroll`：布局前重复重建后的滚动位置断言。
  - `DiagnosticExportUiTest.missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay`：仍要求长按选择文本的旧断言，与本版本“复制全部”按钮行为不一致。

此次发布补全保留现有 APK 和源码标签，未将上述失败记为通过，也未改变应用运行代码。

下载站七种语言的版本标识、更新亮点、APK 下载及 Release 链接同步到 v0.2.59；英文、简体和繁体安装说明区分同签名覆盖升级与不同签名重新安装。

本次自动化与文件校验不代表实车验收。Android 4.2/4.4 T3 车机与 iPhone 的实际 USB 连接、重复重连、视频流畅度、音频及覆盖安装仍需真机复测；可按 [T3 有线连接测试计划](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/v0.2.59/T3-WIRED-TEST-PLAN.md) 执行。最低 API 声明不等于所有车机 ROM 均已验证。

## English

- Use the actual Android API level for USB, VPN, Bluetooth, media, settings and APK installation on head units that report a misleading SDK version.
- Correct wired AirPlay IPv6 interface-scope lookup and add mDNS service-enumeration responses.
- Reuse an available VPN TUN during wired retries and handle the reused transport state, reducing stale Android 4.4 fwmark failures.
- Try bundled Conscrypt for ChaCha20-Poly1305 frame decryption when the platform lacks it, retaining the existing fallback.
- Repair legacy APK installation and settings rebuild crashes; provide an explicit diagnostic-report copy button and redact crash traces line by line.

Package: `com.shihab.diplay`, version `0.2.59` / code `79`, minimum API `17`. The APK retains the v0.2.21 signing certificate. Physical head-unit/iPhone compatibility and performance require device testing.
