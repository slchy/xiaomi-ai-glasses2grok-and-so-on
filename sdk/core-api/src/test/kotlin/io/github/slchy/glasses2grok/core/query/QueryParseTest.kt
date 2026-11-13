package io.github.slchy.glasses2grok.core.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 查询回包解析 —— 用例里的字节全部是 **2026-11-10 真机原样回包**
 * （实测），不是构造的。
 *
 * 这样一旦解析器改动导致字段错位，测试立刻红。
 */
class QueryParseTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `设备信息 - 真机 57 字节`() {
        val payload = hex(
            "1a 37 0a 0e 38 38 38 38 38 38 38 38 38 38 38 38 38 38 " +
                "12 08 31 2e 38 2e 31 32 2e 30 " +
                "1a 10 31 32 33 34 35 36 37 38 39 30 31 32 33 34 35 36 " +
                "22 09 64 65 76 20 67 6c 61 73 73",
        )
        val info = assertNotNull(DeviceInfo.parse(payload))
        assertEquals("88888888888888", info.deviceId)
        assertEquals("1.8.12.0", info.firmware, "固件版本必须与 VERSION-PIN 一致")
        assertEquals("1234567890123456", info.serial)
        assertEquals("dev glass", info.model)
    }

    @Test
    fun `设备状态 - 真机 8 字节电量 79 未充电`() {
        val status = assertNotNull(DeviceStatus.parse(hex("12 06 0a 04 08 4f 10 02")))
        assertEquals(79, status.batteryPercent)
        assertEquals(DeviceStatus.CHARGE_NOT_CHARGING, status.chargeStatus)
        assertEquals(0, status.chargeInfoState, "参考实现不下发 chargeInfo")
        assertEquals(false, status.isCharging)
    }

    @Test
    fun `设备状态 - 充电中与充满的枚举`() {
        val charging = assertNotNull(DeviceStatus.parse(hex("12 06 0a 04 08 64 10 01")))
        assertEquals(100, charging.batteryPercent)
        assertEquals(DeviceStatus.CHARGE_CHARGING, charging.chargeStatus)
        assertEquals(true, charging.isCharging)

        val full = assertNotNull(DeviceStatus.parse(hex("12 06 0a 04 08 64 10 03")))
        assertEquals(true, full.isFull)
    }

    @Test
    fun `设备状态 - chargeInfo 可选段能解析`() {
        // f2{ f1{ f1=55 f2=1 f3{ f1=7 f2=<epoch 秒> } } }
        val status = assertNotNull(
            DeviceStatus.parse(hex("12 10 0a 0e 08 37 10 01 1a 08 08 07 10 f0 ef d0 d7 06")),
        )
        assertEquals(55, status.batteryPercent)
        assertEquals(DeviceStatus.CHARGE_CHARGING, status.chargeStatus)
        assertEquals(7, status.chargeInfoState)
        assertEquals(1794390000, status.chargeInfoTimestamp)
    }

    @Test
    fun `媒体摘要 - 真机 20 字节三条目`() {
        val summary = assertNotNull(
            MediaSummary.parse(
                hex("6a 12 0a 04 08 00 10 02 0a 04 08 02 10 00 0a 04 08 05 10 00"),
            ),
        )
        assertEquals(listOf(MediaEntry(0, 2), MediaEntry(2, 0), MediaEntry(5, 0)), summary.entries)
    }

    @Test
    fun `媒体列表 - 真机空列表 72 00`() {
        val list = assertNotNull(MediaList.parse(hex("72 00")))
        assertEquals(emptyList(), list.entries)
    }

    @Test
    fun `录音状态 - 真机 36 字节`() {
        val st = assertNotNull(
            RecordStatus.parse(
                hex(
                    "9a 01 21 0a 06 08 d2 60 10 a0 06 1a 17 0a 13 0a 04 0a 00 10 00 " +
                        "10 05 18 00 20 c4 c1 d1 cd 9c 34 28 00 10 01",
                ),
            ),
        )
        assertEquals(12370, st.duration?.current, "参考实现字段名 duration.current")
        assertEquals(800, st.duration?.max)
        assertEquals(1, st.status, "extraData.status：空闲=1（真机对照）")
        assertEquals(5, st.mediaFile?.type, "MediaFile.type = OPUS")
        assertEquals(0, st.mediaFile?.size)
        assertEquals(0, st.mediaFile?.duration)
        assertTrue(st.isAudioFile, "OPUS ⇒ 音频录音")
        assertTrue(!st.isVideoFile)
        assertTrue(!st.isRecording, "路径为空 + status=1 ⇒ 空闲")
        assertEquals(null, st.recordingSeconds, "空闲时 duration.current 是内部计时，不能当录音时长")
    }

    @Test
    fun `录音状态 - 时间戳是 epoch 毫秒`() {
        // 2026-11-11 前后的 epoch ms 约 1.794e12；同一时刻抓的音频文件名是
        // audio-1794385175901.sbc，与此值只差约 228 s —— 足以确认单位是毫秒。
        val st = assertNotNull(
            RecordStatus.parse(
                hex(
                    "9a 01 21 0a 06 08 d2 60 10 a0 06 1a 17 0a 13 0a 04 0a 00 10 00 " +
                        "10 05 18 00 20 c4 c1 d1 cd 9c 34 28 00 10 01",
                ),
            ),
        )
        val ts = assertNotNull(st.mediaFile).timestamp
        assertEquals(1_794_385_404_100L, ts)
        assert(ts in 1_700_000_000_000L..1_900_000_000_000L) {
            "时间戳不在 2023–2030 的毫秒区间：$ts"
        }
    }

    @Test
    fun `录音中 - 真机样本给 status=0 且路径非空`() {
        // 2026-11-11 19:35:45 真机：手机发 id=20 开始录音后第 7 秒的状态
        val st = assertNotNull(
            RecordStatus.parse(
                hex(
                    "9a 01 4d 0a 05 08 07 10 a0 06 1a 44 0a 40 0a 31 0a 2d 2f 73 64 63 61 72 64 2f " +
                        "44 43 49 4d 2f 43 61 6d 65 72 61 2f 41 55 44 5f 32 30 32 36 31 31 31 31 31 " +
                        "39 33 35 33 35 33 31 37 2e 6f 67 67 10 00 10 05 18 8d df 07 20 d2 ff b2 0b " +
                        "28 07 10 00",
                ),
            ),
        )
        assertTrue(st.isRecording, "status=0 + 路径非空 ⇒ 正在录音")
        assertTrue(st.isAudioFile, "type=5 = OPUS")
        assertEquals(7, st.duration?.current, "已录 7 秒")
        assertEquals(7, st.recordingSeconds, "录音中才有时长")
        assertEquals(126_861, st.mediaFile?.size, "文件在长大（30 KB/s 量级）")
        assertEquals(7, st.mediaFile?.duration)
        assertTrue(
            st.mediaFile?.id?.endsWith("AUD_20261111193535317.ogg") == true,
            "录音落在眼镜的 /sdcard/DCIM/Camera/AUD_*.ogg",
        )
    }

    @Test
    fun `双指开始录音 - 设备主动上报 id=22 的真机样本`() {
        // 2026-11-11 19:41:53 双指手势触发录音后，设备主动发来的 Media id=22
        // （手机侧在这轮实验里**没有发任何命令**，所以这条只能是设备自己的）
        val st = assertNotNull(
            RecordStatus.parse(
                hex(
                    "9a 01 4b 0a 05 08 00 10 a0 06 1a 42 0a 3e 0a 31 0a 2d 2f 73 64 63 61 72 64 2f " +
                        "44 43 49 4d 2f 43 61 6d 65 72 61 2f 41 55 44 5f 32 30 32 36 31 31 31 31 31 " +
                        "39 34 31 35 30 32 39 37 2e 6f 67 67 10 00 10 05 18 62 20 a2 f1 c9 0b 28 00 " +
                        "10 00",
                ),
            ),
        )
        assertTrue(st.isRecording, "status=0 + 路径非空 ⇒ 录音中（刚起，还没写多少字节）")
        assertEquals(0, st.duration?.current, "刚按下，已录 0 秒")
        assertEquals(98, st.mediaFile?.size, "文件刚建（98 B）")
        assertTrue(
            st.mediaFile?.id?.endsWith("AUD_20261111194150297.ogg") == true,
            "设备侧录音同样落在 /sdcard/DCIM/Camera/AUD_*.ogg",
        )
    }

    @Test
    fun `WiFi 配置 - 真机 39 字节明文凭据`() {
        val cfg = assertNotNull(
            WifiConfig.parse(
                hex(
                    "8a 02 24 0a 20 0a 0e 0a 08 47 4c 41 53 53 43 41 50 10 02 18 02 " +
                        "12 0e 0a 0c 67 6c 61 73 73 63 61 70 31 32 33 34 10 01",
                ),
            ),
        )
        assertEquals("GLASSCAP", cfg.ssid)
        assertEquals("glasscap1234", cfg.psk, "与实测 9.5 记录的热点凭据一致")
    }

    // ---------------------------------------------------- System id=14 读设置
    // 以下回包全部是 2026-11-11 真机原样字节（实测）。

    @Test
    fun `读设置请求 - bitmask 编码与真机发送的字节一致`() {
        // 这四条都是实际发出去的 f4 内容，回包见下面的用例
        assertEquals("92 02 02 08 00", SystemSettingRequest.encode(0).toHex())
        assertEquals("92 02 03 08 80 01", SystemSettingRequest.encode(0x80).toHex())
        assertEquals("92 02 03 08 f0 01", SystemSettingRequest.encode(0xF0).toHex())
        assertEquals("92 02 03 08 f0 03", SystemSettingRequest.encode(0x1F0).toHex())
    }

    @Test
    fun `读设置请求 - 默认 bitmask 正好是本机可用的四段`() {
        val sum = SystemSettingRequest.BIT_INDICATOR_LIGHT or
            SystemSettingRequest.BIT_AI_ASSISTANT or
            SystemSettingRequest.BIT_MEDIA or
            SystemSettingRequest.BIT_HEADSET
        assertEquals(sum, SystemSettingRequest.ALL_SUPPORTED)
        assertEquals(0xF0, SystemSettingRequest.ALL_SUPPORTED)
        assertEquals(0, SystemSettingRequest.ALL_SUPPORTED and 0xF0.inv(), "不该有 f5-f8 之外的位")
    }

    @Test
    fun `读设置 - bitmask 0 只回 f9`() {
        val setting = assertNotNull(SystemSetting.parse(hex("9a 02 04 4a 02 08 00")))
        assertNull(setting.indicatorLight)
        assertNull(setting.aiAssistant)
        assertNull(setting.media)
        assertNull(setting.headset)
        assertEquals(0L, assertNotNull(setting.ota).silentUpdate, "f9 无条件返回")
        assertEquals(setOf(9), setting.rawGroups.keys)
    }

    @Test
    fun `读设置 - bitmask 0x10 只回 f5 指示灯`() {
        val setting = assertNotNull(
            SystemSetting.parse(hex("9a 02 0c 2a 06 08 01 10 1e 18 01 4a 02 08 00")),
        )
        val light = assertNotNull(setting.indicatorLight)
        assertEquals(1L, light.brightness)
        assertEquals(30L, light.lowerLevelsSupported)
        assertEquals(1L, light.chargingLight)
        assertNull(setting.media, "没要 0x40 就不该有 f7")
        assertEquals(setOf(5, 9), setting.rawGroups.keys)
    }

    @Test
    fun `读设置 - bitmask 0x20 只回 f6 AI 助手`() {
        val setting = assertNotNull(
            SystemSetting.parse(hex("9a 02 10 32 0a 08 01 10 01 18 01 20 01 28 01 4a 02 08 00")),
        )
        val ai = assertNotNull(setting.aiAssistant)
        assertEquals(1L, ai.voiceWakeup)
        assertEquals(1L, ai.cameraAccess)
        assertEquals(1L, ai.collaborationWakeup)
        assertEquals(1L, ai.supportedTones)
        assertEquals(1L, ai.voiceTone)
        assertEquals(0L, ai.eyeTracking, "f6 本机缺该字段")
        assertNull(setting.indicatorLight)
    }

    @Test
    fun `读设置 - bitmask 0x40 只回 f7 媒体`() {
        val setting = assertNotNull(
            SystemSetting.parse(
                hex("9a 02 16 3a 10 08 01 10 3c 18 00 20 01 28 01 30 01 38 01 40 01 4a 02 08 00"),
            ),
        )
        val media = assertNotNull(setting.media)
        assertEquals(1L, media.shootingMode)
        assertEquals(60L, media.videoDurationSec)
        assertEquals(0L, media.autoSync)
        assertEquals(1L, media.radioDirection)
        assertEquals(1L, media.tempControl)
        assertEquals(1L, media.shieldReminder)
        assertEquals(1L, media.hintVolume)
        assertEquals(1L, media.recordControl)
        assertEquals(0L, media.previewOnWatch, "f9 及以后本机没下发")
        assertNull(setting.headset)
    }

    @Test
    fun `读设置 - bitmask 0x80 只回 f8 耳机`() {
        val setting = assertNotNull(
            SystemSetting.parse(hex("9a 02 0e 42 08 08 00 10 01 18 00 20 01 4a 02 08 00")),
        )
        val headset = assertNotNull(setting.headset)
        assertEquals(0L, headset.wearDetection)
        assertEquals(1L, headset.volumeAdaptive)
        assertEquals(0L, headset.preventSoundLeak)
        assertEquals(1L, headset.musicControl)
        assertNull(setting.media)
    }

    @Test
    fun `读设置 - 全量回包五段齐全`() {
        val setting = assertNotNull(
            SystemSetting.parse(
                hex(
                    "9a 02 34 2a 06 08 01 10 1e 18 01 " +
                        "32 0a 08 01 10 01 18 01 20 01 28 01 " +
                        "3a 10 08 01 10 3c 18 00 20 01 28 01 30 01 38 01 40 01 " +
                        "42 08 08 00 10 01 18 00 20 01 " +
                        "4a 02 08 00",
                ),
            ),
        )
        assertEquals(setOf(5, 6, 7, 8, 9), setting.rawGroups.keys)
        assertEquals(1L, assertNotNull(setting.indicatorLight).brightness)
        assertEquals(1L, assertNotNull(setting.aiAssistant).voiceWakeup)
        assertEquals(60L, assertNotNull(setting.media).videoDurationSec)
        assertEquals(1L, assertNotNull(setting.headset).musicControl)
        assertEquals(0L, assertNotNull(setting.ota).silentUpdate)
        // 写回 id=15 时整段重发要用到它
        assertEquals("08 00 10 01 18 00 20 01", setting.rawGroups[8])
    }

    @Test
    fun `读设置 - 外层 f35 缺失返回 null`() {
        assertNull(SystemSetting.parse(hex("00 00")))
    }

    // ------------------------------------------------ System id=15 写设置
    // 写帧格式 = f35{ 段号 { 整段 } }；payload 见下面第一条（2026-11-11 真机实发）。

    @Test
    fun `写设置 - 编码与真机实发的写帧一致`() {
        // 真机发过：改 musicControl 1 -> 2，回执是空 payload，随后 id=14 读回 20 02
        val group = hex("08 00 10 01 18 00 20 02")
        assertEquals("08 00 10 01 18 00 20 02", group.toHex())
        assertEquals(
            "9a 02 0a 42 08 08 00 10 01 18 00 20 02",
            SystemSettingWrite.encode(8, group).toHex(),
        )
    }

    @Test
    fun `写设置 - patch 只改目标子字段其余原样保留`() {
        val before = hex("08 00 10 01 18 00 20 01")
        val after = SystemSettingWrite.patch(before, 4, 2)
        assertEquals("08 00 10 01 18 00 20 02", after.toHex())
        // 再改回
        assertEquals("08 00 10 01 18 00 20 01", SystemSettingWrite.patch(after, 4, 1).toHex())
    }

    @Test
    fun `写设置 - patch 保留本机未识别的子字段与字段顺序`() {
        // f4/f5 是本机 id=14 不会下发的字段，写回时必须原样带上，不能被抹掉
        val before = hex("08 01 10 3c 18 00 20 05 28 07 30 01 38 01 40 01")
        val after = SystemSettingWrite.patch(before, 8, 2)
        assertEquals("08 01 10 3c 18 00 20 05 28 07 30 01 38 01 40 02", after.toHex())
    }

    @Test
    fun `写设置 - patch 目标字段缺失时追加到末尾`() {
        // 段 9（OTA）的 f1 是 varint：tag = 9<<3|0 = 0x48
        assertEquals("08 00 48 00", SystemSettingWrite.patch(hex("08 00"), 9, 0).toHex())
    }

    @Test
    fun `写设置 - patch 不破坏 wire2 子消息`() {
        // f2 是嵌套消息（OTA autoDownload/autoUpgrade），改 f1 不能动它
        val before = hex("08 00 12 04 08 05 10 06")
        val after = SystemSettingWrite.patch(before, 1, 1)
        assertEquals("08 01 12 04 08 05 10 06", after.toHex())
    }

    @Test
    fun `写设置 - patch 遇到畸形输入原样返回不截断`() {
        val broken = hex("08 01 12 09 08")
        assertEquals(broken.toHex(), SystemSettingWrite.patch(broken, 1, 2).toHex())
    }

    @Test
    fun `写设置 - value 能按段号子字段号取回原始值`() {
        val setting = assertNotNull(
            SystemSetting.parse(hex("9a 02 0e 42 08 08 00 10 01 18 00 20 02 4a 02 08 00")),
        )
        assertEquals(2L, setting.value(8, 4))
        assertEquals(0L, setting.value(8, 1))
        assertEquals(0L, setting.value(9, 1))
        assertNull(setting.value(7, 1), "回包里没有 f7")
        assertNull(setting.value(8, 9), "f8 里没有子字段 9")
    }

    /**
     * 真机回包里 `f7.f1=1 f2=60 f3=0` 其余全是 1，抓不出「下标错位」这类错误。
     * 这里合成一段**每个子字段值都不同**的载荷，把 13/6/3/4 个下标逐个钉死。
     */    @Test
    fun `读设置 - 每个子字段的顺序用递增合成值钉死`() {
        val setting = assertNotNull(
            SystemSetting.parse(
                hex(
                    // f35（长度 = 8+14+28+10+10 = 70 = 0x46）
                    "9a 02 46 " +
                        // f5: 1,2,3
                        "2a 06 08 01 10 02 18 03 " +
                        // f6: 1..6
                        "32 0c 08 01 10 02 18 03 20 04 28 05 30 06 " +
                        // f7: 1..13
                        "3a 1a 08 01 10 02 18 03 20 04 28 05 30 06 38 07 40 08 48 09 50 0a 58 0b 60 0c 68 0d " +
                        // f8: 1..4
                        "42 08 08 01 10 02 18 03 20 04 " +
                        // f9: f1=1, f2{f1=2 f2=3}
                        "4a 08 08 01 12 04 08 02 10 03",
                ),
            ),
        )
        val light = assertNotNull(setting.indicatorLight)
        assertEquals(listOf(1L, 2L, 3L), listOf(light.brightness, light.lowerLevelsSupported, light.chargingLight))
        val ai = assertNotNull(setting.aiAssistant)
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            listOf(ai.voiceWakeup, ai.cameraAccess, ai.collaborationWakeup, ai.supportedTones, ai.voiceTone, ai.eyeTracking),
        )
        val media = assertNotNull(setting.media)
        assertEquals(
            (1L..13L).toList(),
            listOf(
                media.shootingMode, media.videoDurationSec, media.autoSync, media.radioDirection,
                media.tempControl, media.shieldReminder, media.hintVolume, media.recordControl,
                media.previewOnWatch, media.livePhoto, media.ratioQuality,
                media.slowMotionDuration, media.exerciseDelayDuration,
            ),
            "字段号由 MediaSetting.writeTo 静态确定，顺序错一位这里就红",
        )
        val headset = assertNotNull(setting.headset)
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            listOf(headset.wearDetection, headset.volumeAdaptive, headset.preventSoundLeak, headset.musicControl),
        )
        val ota = assertNotNull(setting.ota)
        assertEquals(listOf(1L, 2L, 3L), listOf(ota.silentUpdate, ota.autoDownload, ota.autoUpgrade))
    }

    @Test
    fun `外层字段缺失时返回 null 而不是抛异常`() {
        assertNull(DeviceInfo.parse(hex("00 00")))
        assertNull(DeviceStatus.parse(hex("00 00")))
        assertNull(WifiConfig.parse(hex("00 00")))
        assertNull(MediaSummary.parse(hex("00 00")))
        assertNull(MediaList.parse(hex("00 00")))
        assertNull(RecordStatus.parse(hex("00 00")))
    }

    @Test
    fun `重复字段要全部取到 - fieldsMulti 与 Pb_fields 的区别`() {
        // 同一个 field 1 出现三次；Pb.fields 只会留最后一个
        val data = hex("0a 02 08 01 0a 02 08 02 0a 02 08 03")
        assertEquals(3, fieldsMulti(data, 1).size)
        assertEquals(1, io.github.slchy.glasses2grok.core.pb.Pb.fields(data).size)
    }
}
