package com.yanfei.r90alarm

import com.yanfei.r90alarm.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比对单元测试。
 *
 * 为什么值得测：这段逻辑直接决定「要不要提示有更新」。
 * 判反了会把已是最新的用户推去下旧包，或该提示时一声不吭。
 * GitHub 的 `tag_name` 又极不规范（v 前缀、段数不一、beta 后缀），必须逐一钉死。
 */
class VersionCompareTest {

    @Test
    fun `完全相同 返回 0`() {
        assertEquals(0, VersionCompare.compare("0.2", "0.2"))
        assertEquals(0, VersionCompare.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `v 前缀被忽略`() {
        assertEquals(0, VersionCompare.compare("v0.2", "0.2"))
        assertEquals(0, VersionCompare.compare("V1.0.0", "1.0.0"))
        assertTrue(VersionCompare.compare("v0.3", "0.2") > 0)
    }

    @Test
    fun `主版本号变大 判为新`() {
        assertTrue(VersionCompare.compare("1.0.0", "0.9.9") > 0)
    }

    @Test
    fun `次版本号变大 判为新`() {
        assertTrue(VersionCompare.compare("0.3", "0.2") > 0)
        assertTrue(VersionCompare.compare("0.10", "0.9") > 0) // 数字比较，不是字符串比较
    }

    @Test
    fun `段数不足按 0 补齐`() {
        assertEquals(0, VersionCompare.compare("0.2", "0.2.0"))
        assertTrue(VersionCompare.compare("0.2.1", "0.2") > 0)
    }

    @Test
    fun `预发布后缀比正式版旧`() {
        // 0.3-beta 在 0.3 正式版之前发布，所以 0.3-beta < 0.3
        assertTrue(VersionCompare.compare("0.3-beta", "0.3") < 0)
        assertTrue(VersionCompare.compare("0.3", "0.3-beta") > 0)
    }

    @Test
    fun `无法识别的后缀不误判为更高`() {
        // 数字段相同 + 一边有后缀 → 有后缀那侧更旧，绝不因后缀"更长"就判更大
        assertFalse(VersionCompare.isNewer("0.2-rc1", "0.2"))
    }

    @Test
    fun `两个后缀按字典序比较`() {
        // "rc1" > "beta"
        assertTrue(VersionCompare.compare("0.3-rc1", "0.3-beta") > 0)
        assertTrue(VersionCompare.compare("0.3-beta", "0.3-rc1") < 0)
    }

    @Test
    fun `isNewer 语义`() {
        assertTrue(VersionCompare.isNewer("0.3", "0.2"))
        assertFalse(VersionCompare.isNewer("0.2", "0.2"))
        assertFalse(VersionCompare.isNewer("0.1", "0.2"))
    }

    @Test
    fun `脏输入不抛异常`() {
        // 空串 / 纯字母都当 0 处理，不能把「检查更新」整条路炸掉
        assertEquals(0, VersionCompare.compare("", ""))
        assertEquals(0, VersionCompare.compare("abc", ""))
        assertTrue(VersionCompare.compare("0.1", "") > 0)
    }
}
