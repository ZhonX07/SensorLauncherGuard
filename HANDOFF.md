# SensorLaunchGuard — 交接说明

依赖 libxposed API 102 的 LSPosed / Vector 模块：按应用屏蔽陀螺仪访问、阻止其拉起其他应用组件。
界面为 Material Design 3，可读取完整已安装应用列表。

> 2026-09-12 / v1.1.0：已修复 v1.0 把“目标包规则”误当成“发起方规则”的关键错误；
> 增加隐式 Activity/Service 解析、Service/显式广播、IntentSender/PendingIntent 路径，
> 并补齐 DirectChannel、受限轴陀螺仪及已注册监听的事件级抑制。以下历史排查记录保留，
> 最新使用说明与准确边界以 `README.md` 为准。

## 技术栈与构建

| 项目 | 值 |
| --- | --- |
| JDK | `E:\JDK\zulu21.52.15-ca-jdk21.0.12-win_x64`（构建前设 `JAVA_HOME`） |
| Gradle | 9.3.1（wrapper，已随仓库提供 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`） |
| AGP | 9.1.1（内置 Kotlin，无需单独声明 kotlin 插件） |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| libxposed | `api:102.0.0`（compileOnly）+ `service:102.0.0`（implementation） |

```powershell
$env:JAVA_HOME="E:\JDK\zulu21.52.15-ca-jdk21.0.12-win_x64"
.\gradlew.bat :app:assembleDebug :app:lint        # 日常
.\gradlew.bat :app:assembleRelease :app:lintRelease  # 发布（需自行配置签名）
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（applicationId 带 `.debug` 后缀）。

## 代码结构

```
data/
  AppInfo.kt          应用数据模型（packageName/label/uid/isSystem/icon/两条规则）
  RuleSnapshot.kt     规则快照（两个不可变包名集合），UI 与钩子共用
  RuleStore.kt        远程偏好读写 + Map -> RuleSnapshot 解析（键：gyro:<pkg>、launch:<pkg>）
  AppRepository.kt    全量应用扫描（Collator 本地化排序、启用规则置顶、名称/包名搜索、全部/用户/系统过滤）
GuardApplication.kt   XposedServiceHelper 监听，持有 XposedService，向已注册 Activity 广播连接状态
MainActivity.kt       ViewBinding + RecyclerView，状态卡/搜索/过滤/计数/加载条/空视图/菜单刷新
ui/AppListAdapter.kt  ListAdapter + DiffUtil，复用 item_app.xml，绑定前清空开关监听器
xposed/
  GuardModule.kt      入口（java_init.list 声明），onModuleLoaded 建规则缓存，onPackageReady 装钩子
  RulesHolder.kt      远程偏好变更监听 -> volatile 规则快照（开关实时生效，无需重启进程）
  Hooks.kt            钩子安装公共逻辑 + 按返回类型合成「空结果」（void→null、boolean→false、int→0）
  SensorHooks.kt      陀螺仪：getDefaultSensor 返回 null、getSensorList 过滤、registerListenerImpl 返回 false
  LaunchHooks.kt      Instrumentation.execStartActivity* + ContextImpl.startActivity* 两条路径
  PackageResolver.kt  宿主包名（由 onPackageReady 注入）与进程归属集合
res/values/attrs.xml  guardSuccessContainer / guardWarningContainer 等状态卡配色属性（明暗主题各一份）
```

## 真机实测踩到的两个坑（重要，勿回退）

这两个都是「开关打开了、但行为没变化」的静默失败，日志里只看得到症状：

### 1. 拉起拦截漏了 `Context.startActivity` 路径

`Activity.startActivity()` 走 `Instrumentation.execStartActivity()`，但 **`Context.startActivity()`
完全不经过 Instrumentation**，直接调 `ActivityTaskManager.getService().startActivity()`。
哔哩哔哩评论区的内置浏览器点京东链接走的正是后者，系统日志证据：

```
ActivityTaskManager: START u0 {act=VIEW dat=openapp.jdmobile://virtual/... 
  cmp=com.jingdong.app.mall/.open.InterfaceActivity} from uid 10250 (tv.danmaku.bili)
```

只挂 Instrumentation 时，钩子确实被调用了，但返回的「空结果」被 `Activity.startActivityForResult`
当成「启动成功」吞掉，真实启动仍然发往 system_server。

修复：额外挂 `ContextImpl.startActivity*`。注意 **包名是 `android.app.ContextImpl`**，
写成 `android.content` 会静默退化成 `ClassNotFoundException`，症状与没修一样。
只挂这两处即可 —— `ContextWrapper` / `Activity` 最终都汇入 `ContextImpl`，多挂一层会对同一次启动重复拦截。

### 2. `SystemSensorManager` 上不存在 `registerListener`

现代 Android（7.0 起）的注册实现在 **`registerListenerImpl`**，
`SensorManager.registerListener()` 的所有公开重载最终都汇入它。
实测 `SystemSensorManager.declaredMethods` 的 51 个方法里没有任何 `registerListener`，
所以最初按名字枚举 `registerListener*` 的做法装上了 **0 个**钩子（日志：`registration hooks installed: 0`）。

修复：改为枚举 `registerListener` / `registerListenerImpl` 中返回 `boolean` 的方法。
实测签名：`registerListenerImpl(SensorEventListener,Sensor,int,Handler,int,int)`。

**排查手法**：钩子安装日志会打印被钩方法的完整签名
（`hooked <类名>#<方法名>(<参数类型>)`）。遇到「装了 0 个」先看这行，不要靠猜。
`launch hooks installed: instrumentation=N contextImpl=M` 同理——`contextImpl=0` 就是路径没挂上。

## 关键设计决定

- **规则实时生效**走远程偏好 + `OnSharedPreferenceChangeListener`，不使用热重载
  （libxposed 文档明确说明 hot reload 不应用于配置同步）。
- **不设 `staticScope` / `scope.list`**：作用域由用户在管理器里勾选，UI 侧在目标应用不在作用域时
  调用 `XposedService.requestScope()` 动态申请；批准后提示重启目标应用，失败回滚开关。
- **钩子只作用于作用域内进程**：`onPackageReady` 仅在框架已勾选的应用进程触发，因此无需再判断包名。
- **失败只记日志**：每个钩子独立 try/catch，绝不向宿主抛异常；目标包解析不到时选择「放行」而非「拦截」，
  避免误伤应用内部跳转。
- **release 包必须保留入口类名**：`java_init.list` 是资源文件、R8 不会改写它，
  因此 `proguard-rules.pro` 用 `-keepnames`（而非 `allowobfuscation`）。
  曾实测 `allowobfuscation` 会把入口类改名成 `yf`，导致 release 包无法加载模块。
- **lint 基线**：已定向 disable `PrivateApi`（反射访问 `SystemSensorManager` 是钩子的唯一入口）、
  `OldTargetApi`、`GradleDependency`、`AndroidGradlePluginVersion`、`ObsoleteSdkInt`
  （自适应图标必须放 `mipmap-anydpi-v26`）。当前 `:app:lint` 与 `:app:lintRelease` 均 **0 issue**。

## 验证状态

| 项目 | 状态 |
| --- | --- |
| `:app:assembleDebug` / `:app:assembleRelease` | ✅ 通过 |
| `:app:lint` / `:app:lintRelease` | ✅ No issues found |
| APK 内 `META-INF/xposed/{java_init.list,module.prop}` | ✅ 已核对，`minApiVersion=102`、`targetApiVersion=102` |
| debug APK 装机启动、应用列表界面 | ✅ 无崩溃，`ResumedActivity` 正常 |
| release 包入口类名 / 混淆映射 | ✅ 类名保留，dex 中可查到完整 FQN |
| 框架加载（Vector v2.2 / libxposed API 102） | ✅ `module loaded, framework=Vector api=102` |
| 钩子安装进 `tv.danmaku.bili` | ✅ 覆盖矩阵见下 |
| 目标应用无崩溃 | ✅ 重启哔哩哔哩后无 `FATAL EXCEPTION` |
| `bindService*` 拦截已彻底移除 | ✅ debug/release dex 内均无 `bindServiceAsUser` / `bindIsolatedService` |
| 重复 `onPackageReady` 去重 | ✅ `additional package ready: com.google.android.webview; hooks already installed` |
| **v1.0 开关行为** | ⚠️ 已确认拉起规则方向判断错误；v1.1 已修正，需按新版重新验证 |
| v1.2 拉起询问（通知） | ✅ 开关写入/读取链路已接通；**弹通知本身待真机点一次** |
| v1.3 跳转豁免 | ✅ 构建/lint 通过，规则解析与钩子加载正常（`exemptSources=N`）；**勾选与放行待真机点一次** |

### v1.2 拉起询问：设计取舍（勿回退为「同步弹对话框」）

需求是「弹一个带选项的提醒，让支付等一次性跳转放行」。实现必须知道：

**钩子同步运行在调用 `startActivity` 的线程上（通常就是宿主主线程），不能阻塞等待用户点选**，
否则宿主直接 ANR。所以做成非阻塞形态：

```
命中规则 → 钩子立刻返回「什么都没发生」（不放行、不崩）
         → 弹一条通知：<来源> 正在试图拉起 <目标>。是否允许？ [禁止] [允许（本次）]
         → 「允许（本次）」= 用原 Intent 重新拉起目标
```

**「允许（本次）」为什么不用来源进程重发**：通知里的 `PendingIntent` 由 system_server 代发，
属通知操作特权，可在后台拉起 Activity，**来源进程即使被杀也依然有效**，
因此不需要动态注册广播接收器、也不需要在进程间传递 Intent。
代价是 `Intent` 内自定义 `Parcelable` extra 在原进程已死时可能无法反序列化，
显式 component / data / 系统 extra 都会保留（支付类深链通常是这种）。

**「本次」语义天然一次性**：不写任何持久豁免状态，`RuleSnapshot` 无需扩展豁免集合，
也就没有「豁免该记在谁头上」的难题。

**新增内容**

| 位置 | 说明 |
| --- | --- |
| `xposed/LaunchPrompt.kt` | 通知构造 + 目标可拉起性判断 + 3 秒内同目标去重 |
| `LaunchHooks.block()` | 三个拦截点的统一收口：先 `logBlock`，再尝试 `prompt.request(...)` |
| `RuleKeys.GLOBAL_PROMPT_LAUNCH` | `prompt:launch`，与规则同组，钩子只读一份偏好即可实时生效 |
| `MainActivity` 菜单「拉起时弹通知询问」 | 写入上述标志；`ACTION_DENY` 收到后立即 `finish()` |
| `MainActivity.requestNotificationPermissionIfNeeded()` | 申请**模块自身**的通知权限 |

**两个必须记住的约束**

1. **`LaunchPrompt` 里不能用 `R.string` / `R.drawable`**。它运行在宿主应用进程内，
   那些资源 ID 属于我们的模块 APK，用宿主的 `Context` 解析会取到错误资源甚至抛异常。
   文案是内置常量，图标取 `module.moduleApplicationInfo.icon`。
2. **询问通知归属于「正在跳转的那个应用」**，不是本模块：它以该应用的身份和渠道发出。
   因此只有该应用允许通知时询问才会显示；若它没有通知权限，模块无法代为授予，
   此时自动退回静默拦截。实测哔哩哔哩 `importance=DEFAULT userSet=true`，可以正常显示。

**验证方式**：开启开关后命中规则应出现两行日志——先 `blocked ...`，再 `prompted: source=... target=...`。
只有 `blocked` 没有 `prompted`，说明询问被跳过（开关未开 / 目标不明确 / 宿主无通知权限）。

### v1.3 跳转豁免：一套「小型出站防火墙」

阶段一（GPT 规划的「稳定可用」范围）已落地。核心约束：

> 豁免的是**一条明确的 来源→目标 关系**，不是豁免整个来源应用，也不是全局放行某个支付应用。

**单一裁决入口**：所有跳转判断收敛到 `RuleSnapshot.decide(LaunchRequest)`，
各 Hook 只负责把现场信息整理成 `LaunchRequest`，不再各写一套放行逻辑。
判断顺序即安全边界，改顺序前先想清楚后果：

```
未受限来源 → 自身组件 → 系统选择器 → 临时豁免 → 永久豁免 → 无法解析 → 拦截
```

返回值是 `LaunchDecision` 枚举而不是布尔，因此日志能说明**为什么放行**：
`ALLOW_PERMANENT_EXEMPTION` / `ALLOW_TEMPORARY_EXEMPTION` / `ALLOW_SELF` /
`ALLOW_CHOOSER` / `ALLOW_UNRESOLVED_COMPATIBILITY` / `BLOCK`。

**远程偏好键**（经查 `RemotePreferences` 源码，`getStringSet`/`putStringSet`/`getLong`/`putLong` 均支持）

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `exempt_targets:<source>` | `StringSet` | 该来源可永久拉起的目标包 |
| `exempt_until:<source>:<target>` | `Long` | 临时豁免过期时刻（`currentTimeMillis`） |
| `exempt_chooser:<source>` | `Boolean` | 允许该来源使用系统选择器 |
| `policy:allow_unresolved` | `Boolean` | 目标无法解析时放行（兼容模式，默认开） |

按来源存 `StringSet` 而不是每条规则一段 JSON：钩子侧只需常数级查表，不必遍历全部偏好。

**「允许（本次）」如何接入**：通知被弹出时就写入一条 30 秒的 `exempt_until`。
这样点通知后既直接拉起目标，也让支付 SDK 自行重试同一个 Intent 时同样放行。
窗口结束自动失效，**不留任何持久状态**，`RuleSnapshot` 无需为此扩展授权模型。

**两个刻意的设计选择**

1. **系统选择器单独许可**，不能把 `android` 包当普通目标放行——否则会连带放行系统设置、
   文件选择器、安装器。判定要求同时满足 `ACTION_CHOOSER` 或组件名含 `ResolverActivity`/`ChooserActivity`。
2. **不做「支付 Intent 自动识别」**。任何应用都能构造 `alipays://`，URI 含 `pay` 也不代表是支付。
   常用支付应用列表**只用于打标记和置顶**，放行永远要求用户显式勾选。
   理由：把授权建立在可伪造的特征上，等于把安全边界交给攻击者。

**第一阶段未做**（GPT 规划的后续阶段）：临时通行 UI（倒计时/磁贴）、最近拦截记录、
目标签名验证、Action/scheme 级条件、兼容/严格模式按来源分别设置、规则导入导出。

### 钩子覆盖矩阵（真机实测安装结果）

```
registration hooks installed: 1        registerListenerImpl
direct channel hooks installed: 2      SensorDirectChannel.configure + configureDirectChannelImpl
event dispatch hooks installed: 1      SystemSensorManager$SensorEventQueue.dispatchSensorEvent
launch hooks installed: instrumentation=7 context=25 activitySender=9 pendingIntent=9 intentSender=4
```

### 关键修复：规则语义是 source-based（发起方），不是 target-based

原实现判断的是**目标包**是否被勾选：

```kotlin
return rules.isLaunchBlocked(targetPackage)   // 错：B站→京东 时去查京东有没有被勾选
```

正确语义是判断**当前发起方进程**：

```kotlin
rules.isLaunchBlockedFor(PackageResolver.currentProcessPackages())   // 对：查 B站 自己有没有被勾选
```

这与 UI 语义一致：开关挂在每一行应用上，勾选哔哩哔哩 = 限制哔哩哔哩去拉起别人。
`isSelf()` 保证「受限制的发起方 → 自己的组件」仍然放行，避免误伤应用内部跳转。

### 关键修复：`bindService` 绝不能被拦截

真机实测拦截 `bindIsolatedService` 后哔哩哔哩直接崩溃：

```
Process: tv.danmaku.bili:download
java.lang.IllegalArgumentException: Service not registered
    at android.app.LoadedApk.forgetServiceDispatcher(...)
    at android.app.ContextImpl.unbindService(...)
```

原因：WebView 请求绑定 → 模块伪造 `false` → WebView 仍认为需要解绑 → 系统里没有登记 → 崩溃。
这是「功能不生效」之外的另一类根因：**宿主崩溃**。
`bindService` / `bindServiceAsUser` / `bindIsolatedService` 一律放行；
真正需要跨应用 IPC 隔离时应做成独立开关并成对跟踪绑定/解绑状态，不能复用本规则。

### `module.prop` 的官方键位（重要约束）

[LSPosed 官方 Wiki](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
明确列出的键**只有三个**：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=false
```

当前文件额外写了 `exceptionMode=protective` 与 `autoHotReload=false`。
实测 Vector v2.2 **不会**因为这两个未知键拒绝加载（日志 `module loaded, framework=Vector api=102`
且各层钩子正常安装），因此保留无碍；但它们**不在官方规范内，不保证被解析**——
不要把它们当作生效的行为保证。异常处理模式应通过钩子侧的 `setExceptionMode()` 表达。

### 关于 proguard 规则与 `-adaptresourcefilecontents`

libxposed README 的推荐规则包含 `-adaptresourcefilecontents META-INF/xposed/java_init.list`
（让 R8 在入口类改名时同步重写清单）。但本项目实测走 AGP 9.1.1 + R8 时该指令**没有生效**：
入口类被改名成 `yf`，而 `java_init.list` 里仍是完整类名，产物无法加载。

因此改用更可靠的 `-keepnames`（直接禁止入口类改名，清单无需被重写）：

```proguard
-keepnames public class * extends io.github.libxposed.api.XposedModule
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule { public <init>(); }
```

已验证 release 产物 dex 中存在完整 FQN。

### 设备框架现状

测试机 Android 16（SDK 36）+ **Vector v2.2 (3080)**（`/data/adb/modules/zygisk_vector`），
libxposed API 102 正常，旧模块 `zygisk_lsposed` 仍在但未被使用。

### 待你点的验证

已安装重新构建的 v1.2.0、重启哔哩哔哩、并 force-stop `com.jingdong.app.mall`。
注意：设备当前处于锁屏状态，我无法驱动界面，所以菜单开关需要你手动打开一次。

1. 打开模块界面 → 右上角菜单 → 勾选「拉起时弹通知询问」（默认关闭，以保持原有静默拦截行为）；
2. 打开哔哩哔哩 → 评论区点一个京东链接 → 应弹出通知
   「哔哩哔哩 正在试图拉起 京东。是否允许？」，带 **[禁止]** 与 **[允许（本次）]**；
3. 点「允许（本次）」→ 应正常跳转京东；点「禁止」→ 不跳转；
4. 同时 `adb logcat -s SensorLaunchGuard` 应看到：

   ```
   blocked activity launch: operation=startActivity source=tv.danmaku.bili target=com.jingdong.app.mall ...
   prompted: source=tv.danmaku.bili target=com.jingdong.app.mall
   ```

   - 只有 `blocked` 没有 `prompted` → 询问被跳过，检查开关是否已开、哔哩哔哩是否允许通知；
   - `source=` 不是哔哩哔哩 → `PackageResolver` 主包名判定有问题；
   - 两行都没有 → 该跳转没走任何已挂载路径，把当时的
     `ActivityTaskManager: START ... from uid <N>` 日志发我，`uid` 能直接定位发起方；
5. 陀螺仪：开着「屏蔽陀螺仪」的应用里，陀螺仪类功能应失效（含规则打开前已注册的监听）。

## 已知限制

已迁移到 `README.md` 的“已知边界”章节。v1.1 已覆盖事件级传感器抑制、DirectChannel 新配置、
隐式 Activity/Service、Service/显式广播、IntentSender 与应用进程内发送的 PendingIntent；
剩余主要边界是 NDK 传感器、已活跃 DirectChannel、直接 Binder 调用及 system_server 未来代发任务。
`bindService` 经真机验证会导致 WebView 在失败后错误解绑而崩溃，因此 v1.1 明确放行跨包绑定。
