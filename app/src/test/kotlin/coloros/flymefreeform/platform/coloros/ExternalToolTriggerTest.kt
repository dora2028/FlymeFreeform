package coloros.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部触发通道的准入判定：发送方 UID 白名单 + shell 标志兜底 + 别名字符集。
 * 纯 JVM 用例，不触碰 Intent，也不需要 system_server。
 */
class ExternalToolTriggerTest {
    @Test
    fun acceptsSystemShellAndRootOnly() {
        listOf(0, 1000, 2000).forEach { uid ->
            assertTrue("uid=$uid 应当放行", ExternalToolTrigger.isAllowedSender(uid))
        }
        // 1001 = 电话进程，10123 = 普通三方 App，2001 = 次要 shell：都不属于暴露范围。
        listOf(-1, 1001, 10123, 2001).forEach { uid ->
            assertFalse("uid=$uid 应当拒绝", ExternalToolTrigger.isAllowedSender(uid))
        }
    }

    @Test
    fun rejectsUntrustedSender() {
        assertEquals(
            "SENDER_UID_NOT_ALLOWED",
            ExternalToolTrigger.rejectionReason(10123, false, "clean_storage"),
        )
    }

    @Test
    fun reportsUnavailableSenderIdentity() {
        assertEquals(
            "SENDER_UID_UNAVAILABLE",
            ExternalToolTrigger.rejectionReason(ExternalToolTrigger.UID_UNKNOWN, false, "clean_storage"),
        )
    }

    /**
     * ColorOS 实测：`getSentFromUid()` 恒为 -1，但 `am broadcast` / root 发出的
     * intent 带 `FLAG_RECEIVER_FROM_SHELL`（AMS 只会为 root/shell 保留它）。
     */
    @Test
    fun fallsBackToShellFlagWhenSenderUidUnavailable() {
        assertTrue(ExternalToolTrigger.isTrustedSender(ExternalToolTrigger.UID_UNKNOWN, true))
        assertNull(
            ExternalToolTrigger.rejectionReason(
                ExternalToolTrigger.UID_UNKNOWN,
                true,
                "clean_storage",
            ),
        )
    }

    /** 标志位不能给已识别的三方 App 开后门：UID 明确时一律按白名单判。 */
    @Test
    fun shellFlagCannotRescueUntrustedUid() {
        assertFalse(ExternalToolTrigger.isTrustedSender(10123, true))
        assertEquals(
            "SENDER_UID_NOT_ALLOWED",
            ExternalToolTrigger.rejectionReason(10123, true, "clean_storage"),
        )
    }

    @Test
    fun passesTrustedSenderWithSidebarAlias() {
        listOf("clean_storage", "recent_files", "screen-translate.v2").forEach { alias ->
            assertNull("alias=$alias 应当放行", ExternalToolTrigger.rejectionReason(2000, false, alias))
        }
    }

    @Test
    fun rejectsAliasOutsideSidebarCharset() {
        // 与 SidebarProtocol.isValidToolAlias 同一条规则：长度 ≤ 64、仅字母数字与 _ - .
        listOf(null, "", "clean storage", "clean/storage", "a".repeat(65)).forEach { alias ->
            assertEquals(
                "alias=$alias 应当拒绝",
                "TOOL_ALIAS_INVALID",
                ExternalToolTrigger.rejectionReason(1000, false, alias),
            )
        }
    }

    @Test
    fun nonFiniteClickCoordinatesFallBackToZero() {
        assertEquals(0f, ExternalToolTrigger.sanitizeCoordinate(Float.NaN), 0f)
        assertEquals(0f, ExternalToolTrigger.sanitizeCoordinate(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, ExternalToolTrigger.sanitizeCoordinate(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(321f, ExternalToolTrigger.sanitizeCoordinate(321f), 0f)
    }
}
