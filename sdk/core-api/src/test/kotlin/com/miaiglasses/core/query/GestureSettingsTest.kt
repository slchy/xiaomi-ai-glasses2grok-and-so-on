package com.miaiglasses.core.query

import com.miaiglasses.core.media.RecordCommand
import com.miaiglasses.core.media.RecordKind
import com.miaiglasses.core.media.RecordAction
import com.miaiglasses.core.media.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  命令语义封装：枚举的 `wire` 必须与线上常量表**同源**。
 *
 * 这条测试防的是「枚举和常量表各写一份数字，某天只改了一边」。
 */
class GestureSettingsTest {

    @Test
    fun `媒体控制手势与参考实现值表一致`() {
        assertEquals(1, MusicControlTap.SINGLE.wire)
        assertEquals(2, MusicControlTap.DOUBLE.wire)
    }

    @Test
    fun `拍摄模式与参考实现值表一致`() {
        assertEquals(1, ShootingMode.SHORT_PRESS_PHOTO.wire)
        assertEquals(2, ShootingMode.SHORT_PRESS_RECORD.wire)
    }

    @Test
    fun `录音手势与参考实现值表一致`() {
        assertEquals(1, RecordGesture.DOUBLE_TAP.wire)
        assertEquals(2, RecordGesture.DOUBLE_HOLD.wire)
        assertEquals(3, RecordGesture.OFF.wire)
    }

    @Test
    fun `录音枚举直接引用线上常量而不是抄数字`() {
        assertEquals(RecordType.NORMAL, RecordKind.NORMAL.wire)
        assertEquals(RecordType.REALTIME, RecordKind.REALTIME.wire)
        assertEquals(RecordType.MUSIC, RecordKind.MUSIC.wire)
        assertEquals(RecordAction.START, RecordCommand.START.wire)
        assertEquals(RecordAction.STOP, RecordCommand.STOP.wire)
    }
}
