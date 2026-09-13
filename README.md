# 应用行为守卫（SensorLaunchGuard）

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![libxposed API](https://img.shields.io/badge/libxposed%20API-102-brightgreen.svg)](https://github.com/libxposed/api)
[![Android](https://img.shields.io/badge/Android-8.1%2B-green.svg)](https://developer.android.com)

基于 Modern libxposed API 102 的 LSPosed / Vector 模块。它可以读取当前用户的已安装应用列表，
并为每个应用分别设置：

- **屏蔽陀螺仪**：隐藏标准、未校准、受限轴陀螺仪，拒绝新监听与 DirectChannel，
  同时在事件派发层停止已注册监听的后续回调；
- **禁止拉起其他应用**：阻止被选中的发起方应用启动其它包的 Activity、Service、前台 Service、
  显式广播、`IntentSender` 和直接发送的外部 `PendingIntent`；应用自身组件仍放行；
- **拉起时弹通知询问**（菜单开关，默认关闭）：命中拦截规则时不直接丢弃，而是弹一条通知询问
  「禁止 / 允许（本次）」，便于放行支付等一次性跳转；
- **跳转豁免**：为某个来源应用指定「允许拉起」的目标应用（如哔哩哔哩 → 支付宝）。
  豁免的是**一条明确的 来源→目标 关系**，不是豁免整个来源应用，也不是全局放行某个支付应用；
- Material Design 3 界面，支持动态取色、名称/包名搜索、用户/系统应用筛选和已启用规则置顶；
- 通过 Xposed Service 的 Remote Preferences 实时同步规则，并在需要时向框架动态申请作用域。

## 环境要求

- Android 8.1（API 26）或更高版本；
- 已安装并正常启用支持 **Modern libxposed API 102** 的框架，例如 Vector 2.2 或相应的现代
  LSPosed 实现。旧版、只支持 legacy Xposed API 的框架无法加载本模块；
- Root / Zygisk 环境由所用框架自行要求。

## 安装与使用

1. 安装 `build-artifacts` 中的 APK，并在模块管理器中启用“应用行为守卫”；
2. 打开模块界面。状态卡显示已连接的框架名称后，选择目标应用的规则；
3. 首次为某个应用打开规则时，批准框架弹出的作用域申请；
4. **第一次加入作用域后必须完全结束并重新打开目标应用**，让模块注入目标进程；
5. 已注入进程中的普通规则修改会通过 Remote Preferences 实时生效。关闭该应用的最后一条规则时，
   模块也会把它从作用域移除。

“禁止拉起其他应用”按**发起方**判断：例如给哔哩哔哩打开该规则后，哔哩哔哩拉起京东会被拦截，
无需也不应该给京东打开相同规则。

## 跳转豁免

应用卡片上出现「跳转豁免 · 已允许 N 个应用」入口，点进去为该来源勾选允许拉起的目标应用。
常用支付应用（支付宝、微信、云闪付、浏览器等）会打上「常用支付」标记并置顶。

要点：

- 豁免只影响**已经开启「禁止拉起其他应用」的来源应用**。以哔哩哔哩为例：
  拉起支付宝/微信 = 允许，拉起京东 = 拦截，拉起浏览器 = 拦截（除非浏览器也被豁免）；
- **目标应用不需要加入 LSPosed 作用域**。拦截发生在来源应用进程里，
  把支付宝加进作用域只会白白扩大 Hook 范围；
- 关闭「禁止拉起其他应用」时**豁免配置会被保留**，卡片上显示「已保存 N 个（规则关闭时暂不生效）」。
  重新打开规则后立即恢复生效；
- 判断依据是 UI 里勾选的 来源→目标 关系，**不根据 URI 或包名猜测「这是不是支付」**。
  任何应用都能构造 `alipays://`，自动识别等于把授权交给攻击者；
- 浏览器是特例：允许某个来源拉起浏览器，就等于允许它在该浏览器里打开**任意**可解析链接，
  不限于支付页面。

日志会区分放行原因，便于排查：

```text
launch decision=ALLOW_PERMANENT_EXEMPTION source=... target=... operation=ACTIVITY scheme=alipays
launch decision=ALLOW_TEMPORARY_EXEMPTION ...
launch decision=ALLOW_SELF ...
launch decision=BLOCK ...
```

## 拉起时弹通知询问

在右上角菜单打开「拉起时弹通知询问」后，命中拦截规则时不再静默丢弃，而是弹一条通知：

```text
应用行为守卫
哔哩哔哩 正在试图拉起 京东。是否允许？
[禁止]  [允许（本次）]
```

- **允许（本次）**：只放行这一次，不写入任何持久豁免。通知里的动作由 system_server 代发，
  因此**来源应用的进程即使已被系统回收，点击依然有效**；
- **禁止**：仅关闭通知，执行与静默拦截相同的效果；
- 询问无法进行时（目标不是显式 Intent、宿主没有通知权限）自动退回静默拦截，保持「不放行」。

### 为什么是通知，而不是应用内对话框

拦截发生在**被限制应用自己的进程里**，并且钩子同步运行在调用 `startActivity` 的线程上
（通常就是宿主主线程）。在那里弹出对话框并等待用户点选，会直接把宿主卡到 ANR。
弹通知是非阻塞的，钩子可以立即返回「什么都没发生」。

### 通知归属：为什么有时看不到询问

询问通知由**正在尝试跳转的那个应用**、以它自己的身份和渠道发出。因此：

- 只有该应用允许通知时，询问才会显示（Android 13+ 需该应用拥有 `POST_NOTIFICATIONS`）；
- 在系统设置里看到该通知属于那个应用，而不是本模块，这是预期行为；
- 若该应用的通知被关闭，模块无法代为授予，此时仍按静默拦截处理。

模块自身界面的通知权限与询问能否显示无关，只影响模块自己发出的通知。

## 构建

项目已经包含 Gradle Wrapper。本机配置使用 `E:\sdk`：

```powershell
$env:JAVA_HOME="E:\JDK\zulu21.52.15-ca-jdk21.0.12-win_x64"
.\gradlew.bat :app:assembleDebug :app:lint
```

主要版本：Gradle 9.3.1、AGP 9.1.1、compileSdk 37、targetSdk 36、minSdk 26、
libxposed API / service 102.0.0、Material Components 1.14.0。

Debug APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。Release 变体默认未配置私有签名，
因此产出的是 unsigned APK；发布前请配置自己的 keystore。

## 日志与排错

```powershell
adb logcat -s SensorLaunchGuard
```

首次启动目标进程时应看到 `hooks ready for <包名>`，以及各组 hook 的安装数量。命中规则时会看到：

```text
blocked activity launch: source=<发起方> target=<目标包> ...
blocked gyroscope registration type=4
blocked live gyroscope events type=4
prompted: source=<发起方> target=<目标包>
```

`prompted:` 表示已弹出询问通知；若只看到 `blocked ...` 而没有 `prompted:`，说明询问被跳过
（开关未开、目标不明确、或宿主没有通知权限），此时按静默拦截处理。

若刚批准作用域但没有加载日志，请强行停止并重新打开目标应用。一个进程随后加载 WebView 等额外包时，
日志会显示 `hooks already installed`，不会重复安装钩子。

## 权限与隐私

为了完整显示已安装应用，Manifest 声明了 `QUERY_ALL_PACKAGES`。它没有运行时授权弹窗，应用列表只在
本机用于界面展示和规则选择，不会上传。Google Play 将安装应用列表视为敏感数据；若计划上架，需按
商店政策说明该权限是安全工具的核心功能。

## 已知边界

- 通过 Android NDK `ASensorManager` 直接读取传感器的数据不经过 Java SensorManager，当前 Java 模块
  无法拦截；已在规则开启前建立且仍活跃的 DirectChannel 也可能继续向共享内存写数据；
- 直接调用隐藏 Binder 接口、由 system_server 在未来代发的闹钟/通知 PendingIntent，可能绕过应用进程
  内的启动钩子；
- `bindService` 属于跨应用 IPC 而非用户可见的“拉起”，且 WebView/GMS 等基础组件大量依赖它；模块有意
  放行绑定，避免调用方在伪造绑定失败后错误解绑并崩溃；
- 隐式 Activity/Service 会尽量通过宿主 PackageManager 解析；受 Android 包可见性或厂商改动影响而
  无法解析时选择放行，以避免误伤应用内部流程；隐式广播可能同时命中多个包，因此只拦截显式外部广播；
- 多个 APK 共用同一 Linux 进程时，Android 无法为每次调用可靠区分来源；任一进程归属包启用规则后，
  该共享进程整体按此规则处理；
- Hook 依赖 Android 框架内部实现。代码按方法名和参数类型枚举以兼容常见 AOSP/OEM 变体，但大版本或
  深度定制 ROM 仍可能需要适配。安装数量会写入日志，失败时默认放行而不让宿主崩溃。

## 目录结构

- `app/src/main/java/.../data`：应用扫描、规则模型与远程偏好读写；
- `app/src/main/java/.../ui`：Material 3 应用列表；
- `app/src/main/java/.../xposed`：模块入口、传感器与跨应用启动 Hook；
- `app/src/main/resources/META-INF/xposed`：Modern libxposed 入口和模块配置；
- `build-artifacts`：便于直接安装的构建产物。

## 许可证

[Apache License 2.0](LICENSE)，第三方组件声明见 [NOTICE](NOTICE)。

选用 Apache-2.0 而非 MIT 的原因：本模块依赖的 `libxposed` API 与 Service 均为 Apache-2.0，
且该许可证包含明确的**专利授权**条款（MIT 对专利完全沉默）。本模块需要 hook Android 框架
内部实现，属于技术方案容易被申请专利的领域，专利授权条款是有实际意义的。

本模块运行在 LSPosed / Vector 等 GPL-3.0 框架之上，但**不包含也不链接**这些框架的代码，
因此不受其许可证约束——这是 Xposed 生态的通行模式。

## 开发文档

架构设计取舍、真机实测踩过的坑、以及尚未完成的功能，见 [HANDOFF.md](HANDOFF.md)。
