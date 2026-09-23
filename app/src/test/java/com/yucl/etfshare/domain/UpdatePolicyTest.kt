package com.yucl.etfshare.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「今日是否需要联网抓取」的判定测试。
 *
 * 这是 App 启动自动刷新逻辑的核心：判定错误会导致两种极端 ——
 * 要么每次启动都全量抓取（慢且费流量），要么该更新时不更新。
 */
class UpdatePolicyTest {

    private val now = LocalDateTime.of(2026, 9, 23, 20, 30) // 周三 20:30

    private fun state(
        lastDate: String? = "2026-09-23",
        lastTime: String? = "2026-09-23 20:10:00",
        ok: Boolean = true,
        snapshot: String? = "2026-09-23",
    ) = UpdatePolicy.State(lastDate, lastTime, ok, snapshot)

    @Test
    fun fetchesWhenNothingFetchedToday() {
        val d = UpdatePolicy.decide(state(lastDate = "2026-09-22"), "2026-09-22", now)
        assertFalse(d.skip)
        assertEquals("今日尚未抓取", d.reason)
    }

    @Test
    fun skipsWhenSnapshotAlreadyCoversToday() {
        val d = UpdatePolicy.decide(state(), "2026-09-23", now)
        assertTrue(d.skip)
        assertTrue(d.reason.contains("今日份额快照已在本地"))
    }

    @Test
    fun skipsWhenFetchedAfterDataReadyHour() {
        // 今日抓过一次但快照仍是昨天：盘后（18 点后）抓过就不再重复
        val d = UpdatePolicy.decide(state(lastTime = "2026-09-23 19:05:00"), "2026-09-22", now)
        assertTrue(d.skip)
    }

    @Test
    fun refetchesWhenLastAttemptWasBeforeDataReadyHour() {
        // 早上抓过一次（当时当日份额还没发布），盘后应该补抓一次
        val d = UpdatePolicy.decide(state(lastTime = "2026-09-23 09:10:00"), "2026-09-22", now)
        assertFalse(d.skip)
        assertTrue(d.reason.contains("补抓"))
    }

    @Test
    fun skipsBeforeDataReadyHour() {
        val morning = LocalDateTime.of(2026, 9, 23, 9, 0)
        val d = UpdatePolicy.decide(state(lastTime = "2026-09-23 08:50:00"), "2026-09-22", morning)
        assertTrue(d.skip)
        assertTrue(d.reason.contains("18:00"))
    }

    @Test
    fun skipsOnWeekend() {
        val saturday = LocalDateTime.of(2026, 9, 26, 20, 30)
        val d = UpdatePolicy.decide(
            state(lastDate = "2026-09-26", lastTime = "2026-09-26 20:10:00"),
            "2026-09-25",
            saturday,
        )
        assertTrue(d.skip)
        assertTrue(d.reason.contains("非工作日"))
    }

    @Test
    fun retriesWhenPreviousFetchWasNotOk() {
        val d = UpdatePolicy.decide(state(ok = false), "2026-09-23", now)
        assertFalse(d.skip)
    }

    @Test
    fun launchCooldownSuppressesImmediateRetryAfterFailure() {
        // 10 分钟前刚抓过但核心数据不完整 -> 本次启动不再自动重抓（手动刷新不受限）
        val st = state(lastTime = "2026-09-23 20:20:00", ok = false)
        val d = UpdatePolicy.decideOnLaunch(st, "2026-09-22", now)
        assertTrue(d.skip)
        assertTrue(d.reason.contains("冷却") || d.reason.contains("不足"))
    }

    @Test
    fun launchCooldownExpires() {
        val st = state(lastTime = "2026-09-23 20:00:00", ok = false)
        val d = UpdatePolicy.decideOnLaunch(st, "2026-09-22", now)
        assertFalse(d.skip)
    }

    @Test
    fun cooldownIgnoresYesterdayStamp() {
        assertFalse(UpdatePolicy.withinCooldown("2026-09-22 20:00:00", now))
    }

    @Test
    fun cooldownIgnoresBrokenStamp() {
        assertFalse(UpdatePolicy.withinCooldown("not-a-time", now))
        assertFalse(UpdatePolicy.withinCooldown(null, now))
    }

    @Test
    fun stampParsingIsStrict() {
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 10, 0), UpdatePolicy.parseStamp("2026-09-23 20:10:00"))
        assertEquals(null, UpdatePolicy.parseStamp("2026-09-23"))
        assertEquals(null, UpdatePolicy.parseStamp(""))
    }
}
