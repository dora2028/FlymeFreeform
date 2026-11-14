# 外部触发：按别名执行侧边栏工具

> 适用版本：v0.2.6 之后（含本改动）。改动文件：`ColorOsFreeformCoordinator`、新增 `ExternalToolTrigger`。

扇形的工具条目（小布识屏、屏幕翻译、清理存储……）本质是**侧边栏进程里的 `AbsTool` 处理器**，
不是可启动的 Activity，因此只有 system_server 侧的 `ColorOsSidebarClient.runTool()` 调得动它。
本通道把这条链路开了一个广播入口，供 Tasker / MacroDroid / 快捷指令 / `adb` 之类的外部工具调用。

## 先查真实别名

别名来自 ColorOS 侧边栏自己的工具条目（运行时 `getAlias()`），**不是固定列表**，不同机型/系统版本不一样。
本模块会把拿到的目录写进 `Settings.Global`，照抄下面的命令取第一列：

```bash
# 电脑上通过 adb
adb shell settings get global flymefreeform_tool_catalog_v1 | cut -f1,2,3

# 设备本机（有 root，不需要 adb）
su -c 'settings get global flymefreeform_tool_catalog_v1' | cut -f1,2,3

# 输出形如：screen_shot	截屏	1
#          read_article	AI 妙听	1
```

> **备注**：目录是纯文本、制表符分隔，`cut -f1,2,3` 在 `su` 内还是外执行结果都一样
> （`su` 只负责起进程，`cut` 只是读管道）。若某些机型上 `su -c` 的 stdout 被重定向导致管道取不到内容，
> 把整条塞进 `su` 里：`su -c "settings get global flymefreeform_tool_catalog_v1 | cut -f1,2,3"`。
> 第四列是图标数据，模块写 `Settings.Global` 时已剔除（`Settings` 单值上限 32KB），所以 `-f1,2,3` 刚好够。

> 第三列 `1` 表示当前可用、`0` 表示该工具此刻不可用（例如未登录、需联网）。
> 两个特殊值：`recent_files` 由模块自己实现，无需原厂条目；示例里的 `clean_storage`、`screen_translate`
> 只是占位写法，**若你的目录里没有这个别名，广播不会报错，但侧边栏不会有任何反应**。

## 调用方式

```bash
adb shell am broadcast \
  -a coloros.flymefreeform.action.RUN_TOOL \
  --es tool_alias screen_shot
```

带点击坐标（仅用于原厂 PageRout 记账，可省略）：

```bash
adb shell am broadcast \
  -a coloros.flymefreeform.action.RUN_TOOL \
  --es tool_alias read_article \
  --ef tool_click_x 540 --ef tool_click_y 1200
```

root 场景注意 `su` 之后 `am` 的 UID 会变成 0，同样放行：

```bash
su -c 'am broadcast -a coloros.flymefreeform.action.RUN_TOOL --es tool_alias screen_shot'
```

多用户设备上显式指定用户（默认发往 system user）：

```bash
adb shell am broadcast --user 0 -a coloros.flymefreeform.action.RUN_TOOL --es tool_alias screen_shot
```

## 协议

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| action | — | 是 | `coloros.flymefreeform.action.RUN_TOOL` |
| `tool_alias` | String | 是 | 侧边栏工具别名，长度 ≤ 64，仅字母数字与 `_` `-` `.` |
| `tool_click_x` | Float | 否 | 点击坐标 x，默认 `0`；NaN / ±∞ 回落 `0` |
| `tool_click_y` | Float | 否 | 点击坐标 y，默认 `0`；NaN / ±∞ 回落 `0` |

`tool_alias` 与 `SidebarProtocol.TOOL_ALIAS` 同名同规则，走的是同一条 `RUN_TOOL` 消息，
因此**别名白名单、侧边栏 UID 校验、`UIService` 绑定与解绑、前台服务豁免全部是既有的那一套**，
本通道没有新增任何系统接口。

## 谁能发

广播注册为**导出**（`Context.RECEIVER_EXPORTED`）：`shell`(2000)、`root`(0) 与 system_server(1000)
不是同一 UID，非导出注册根本收不到 `adb shell am broadcast`。
动作没有权限保护，因此身份校验放在接收端，只放行三个 UID：

| UID | 身份 |
| --- | --- |
| 1000 | `SYSTEM_UID`（系统自身 / 其他系统服务） |
| 2000 | `SHELL_UID`（`adb shell am broadcast`） |
| 0 | `ROOT_UID`（`su -c 'am broadcast …'`） |

其余 UID（含普通三方 App、模块 App 自身）一律丢弃并打一行 `EXTERNAL_TOOL_REJECTED SENDER_UID_NOT_ALLOWED uid=…`。
如果以后要让模块 App 自己也能触发，把模块包名的 UID 加进 `ExternalToolTrigger` 白名单即可，
**不要**改成"不校验"。

另外还受一条闸门约束：模块总开关（设置页开关）关闭时不执行，日志为
`EXTERNAL_TOOL_REJECTED MODULE_DISABLED`。横屏 / 游戏模式 / 锁屏**不**拦截 ——
那是给手势用的环境闸门，外部指令是显式调用，不该被它挡住。

## 为什么不是 `Binder.getCallingUid()`

动态注册的接收器，`onReceive` 由**主线程消息队列**派发（`LoadedApk.ReceiverDispatcher` 里
`mActivityThread.post(...)`），调用栈早已离开 binder 事务。此时 `Binder.getCallingUid()`
读到的是线程上一次事务的残留值，在 system_server 里恒为本进程 UID 1000 ——
三个分支全部命中，等于**放行所有发送方**。正确做法是 Android 14 起提供的
`BroadcastReceiver.getSentFromUid()`（取不到身份时返回 `Process.INVALID_UID`，本实现按拒绝处理）。

## 查别名

工具目录长期存放在 `Settings.Global`（去图标文本版，制表符分隔：`alias \t 标签 \t 可用 \t 图标`）：

```bash
adb shell settings get global flymefreeform_tool_catalog_v1 | cut -f1,2,3
# 或设备本机 root：su -c 'settings get global flymefreeform_tool_catalog_v1' | cut -f1,2,3
```

未刷入模块或尚未拿到目录时该值可能为空：先在设备上滑出一次扇形，或打开一次设置页，
`system_server` 会主动向侧边栏索要目录。

## 诊断

模块日志的 code 带 `FlymeFreeform` 前缀，但 **LSPosed 会把它们重定向到 `LSPosedFramework` 这个 tag** 下，
所以 `logcat -s FlymeFreeform` 抓不到，要按内容过滤：

```bash
adb logcat -v time | grep -E "FlymeFreeform.*(EXTERNAL_TOOL|TOOL_BIND|TOOL_INVOKED)"
```

> 设备上直接排查（已 root）：
> `su -c 'logcat -v time > /data/local/tmp/ff.log & sleep 1; cmd activity broadcast -a coloros.flymefreeform.action.RUN_TOOL --es tool_alias <别名>; sleep 3; killall logcat; grep -iE "FlymeFreeform|EXTERNAL_TOOL" /data/local/tmp/ff.log'`

| 日志 | 含义 |
| --- | --- |
| `EXTERNAL_TOOL_RECEIVER_READY` | 接收器注册成功（每次 system_server 启动一行） |
| `EXTERNAL_TOOL_RECEIVER_FAILED` | 注册失败，附异常 |
| `EXTERNAL_TOOL_REJECTED SENDER_UID_UNAVAILABLE uid=-1 shellFlag=false` | 既拿不到发送方 UID，也没有 shell 标志 —— 只有这种情况才算不可信 |
| `EXTERNAL_TOOL_REJECTED SENDER_UID_NOT_ALLOWED uid=…` | 发送方不在白名单 |
| `EXTERNAL_TOOL_REJECTED TOOL_ALIAS_INVALID` | 别名缺失或含非法字符 |
| `EXTERNAL_TOOL_REJECTED MODULE_DISABLED` | 模块总开关关闭 |
| `EXTERNAL_TOOL_REJECTED TOOL_QUEUE_FULL` | 工具队列已满（容量 4 + 1 条执行中，`AbortPolicy` 直接拒绝） |
| `EXTERNAL_TOOL_DISPATCHED <alias> @x,y` | 已交给 `runTool()`，后续结果看侧边栏进程的 `TOOL_INVOKED` / `TOOL_INVOKE_FAILED` |

### 发送方身份是怎么校验的

优先用 Android 14 的 `BroadcastReceiver.getSentFromUid()` 读真实发送方，白名单为
`0`（root）/ `1000`（system）/ `2000`（shell）。

部分 ROM（**实测 ColorOS 就是**）不会把 `callerUid` 填进 `ReceiverDispatcher`，读出来恒为 `-1`。
此时退回 `Intent.FLAG_RECEIVER_FROM_SHELL`：AMS 在 `verifyBroadcastLocked` 里会**主动清除**
非 root/shell 调用方设置的该标志，所以它还能出现在 `onReceive` 里，就说明发送方是 root 或 shell。

注意这个兜底**不能反向使用**：UID 明确是某个三方 App 时，即使标志位残留也一律拒绝。

`EXTERNAL_TOOL_DISPATCHED` 只代表**入队成功**，工具是否真的启动要看侧边栏侧日志
（`TOOL_INVOKED … via=pageRout` / `TOOL_INVOKE_FAILED`）：`runTool()` 是异步的，
绑定失败、工具自身不可用都会在那边体现。

## 边界

- **不是后台拉起 App**：只执行侧边栏工具。需要打开应用时走真正的 `startActivity` / 小窗链路。
- **不持久**：接收器与 system_server 同生命周期，模块总开关/环境暂停不影响注册本身。
- **免会话**：不打开面板、不产生 overlay、不计入"最近小窗"，与扇形触发的工具行为一致。
- **不排队重试**：队列满即拒绝，调用方（Tasker 等）自行重试。
