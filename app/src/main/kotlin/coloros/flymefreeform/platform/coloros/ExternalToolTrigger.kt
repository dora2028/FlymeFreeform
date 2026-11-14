package coloros.flymefreeform.platform.coloros

/**
 * @author bomo 外部工具触发协议：`am broadcast` / 自动化 App / 快捷指令 → 侧边栏工具。
 *
 * 这里只定义**入口契约**（动作名、附加字段、发送方白名单），执行仍交给
 * [ColorOsSidebarClient.runTool]。因此 `UIService` 绑定、侧边栏 UID 校验、
 * 前台服务豁免、别名白名单与队列上限全部复用既有链路，本通道不新增任何系统接口，
 * 也不改动 [SidebarProtocol] 的跨进程消息格式。
 *
 * ```
 * adb shell am broadcast -a coloros.flymefreeform.action.RUN_TOOL \
 *     --es tool_alias clean_storage
 * ```
 *
 * 与扇形的区别只有一点：扇形会把"当前是否允许手势"（横屏 / 游戏模式 / 锁屏）作为前置闸门，
 * 外部触发是**显式指令**，只受模块总开关约束，不受环境暂停影响。
 */
internal object ExternalToolTrigger {
    const val ACTION = "coloros.flymefreeform.action.RUN_TOOL"
    const val EXTRA_TOOL_ALIAS = "tool_alias"
    const val EXTRA_CLICK_X = "tool_click_x"
    const val EXTRA_CLICK_Y = "tool_click_y"

    /**
     * 允许的发送方 UID：`Process.SYSTEM_UID / SHELL_UID / ROOT_UID` 的字面量。
     *
     * 写死字面量而非引用 `Process` 常量，是为了让纯 JVM 单测拿到确定值
     * （android.jar 桩里的静态字段不可靠）。
     *
     * 为什么必须按 UID 校验：接收器声明为导出 —— `shell`(2000) / `root`(0) 与 system_server(1000)
     * 不是同一 UID，非导出注册根本收不到 `adb shell am broadcast`。
     * 广播动作不是保护广播，任何普通 App 都能发，所以**发送方 UID 是这里唯一可用的身份凭据**。
     */
    private const val UID_ROOT = 0
    private const val UID_SYSTEM = 1000
    private const val UID_SHELL = 2000

    /** 取不到发送方（框架未填充 `getSentFromUid()`）时的哨兵值，一律按不可信处理。 */
    const val UID_UNKNOWN = -1

    /**
     * `Intent.FLAG_RECEIVER_FROM_SHELL`（0x00400000）的字面量 —— 该常量是 @hide，公开 SDK 里没有。
     *
     * AMS 在 `verifyBroadcastLocked` 里对非 root/shell 调用方**强制清除**此标志
     * （`Slog.w: Removing FLAG_RECEIVER_FROM_SHELL because caller is UID xxxx`），
     * 因此它到达 onReceive 仍为 1，就等价于「发送方是 root 或 shell」。
     *
     * 这是 `getSentFromUid()` 不可用时的兜底凭据：部分 ROM（实测 ColorOS）不会把
     * callerUid 填进 ReceiverDispatcher，读取结果恒为 -1。
     */
    const val FLAG_RECEIVER_FROM_SHELL = 0x00400000

    fun isAllowedSender(uid: Int): Boolean =
        uid == UID_SYSTEM || uid == UID_SHELL || uid == UID_ROOT

    /**
     * 是否可信：UID 白名单优先，UID 不可用时才退回 shell 标志。
     *
     * 不能反过来判断 —— UID 明确是某个三方 App 时，即使标志位还在也不该放行
     * （AMS 会清除它，这里只是防御性处理）。
     */
    fun isTrustedSender(uid: Int, fromShell: Boolean): Boolean =
        if (uid == UID_UNKNOWN) fromShell else isAllowedSender(uid)

    /**
     * 逐项校验并返回拒绝原因码；通过时返回 null。
     *
     * 拆成不碰 [android.content.Intent] 的纯函数，单测可直接覆盖白名单与别名字符集。
     * 别名规则与 [SidebarProtocol.isValidToolAlias] 完全一致：长度 ≤ 64、仅字母数字与 `_ - .`。
     */
    fun rejectionReason(uid: Int, fromShell: Boolean, alias: String?): String? =
        when {
            !isTrustedSender(uid, fromShell) ->
                if (uid == UID_UNKNOWN) "SENDER_UID_UNAVAILABLE" else "SENDER_UID_NOT_ALLOWED"
            !SidebarProtocol.isValidToolAlias(alias) -> "TOOL_ALIAS_INVALID"
            else -> null
        }

    /**
     * 点击坐标只用于原厂 PageRout 记账（工具本身不依赖它）：
     * NaN / ±∞ 回落 0，避免把非法值写进侧边栏的统计。
     */
    fun sanitizeCoordinate(value: Float): Float = if (value.isFinite()) value else 0f
}
