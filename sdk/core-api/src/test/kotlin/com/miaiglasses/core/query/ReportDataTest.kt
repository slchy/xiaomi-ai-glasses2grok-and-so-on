package com.miaiglasses.core.query

import com.miaiglasses.core.pb.Pb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `System id=80 ReportData` 的回包解析。
 *
 * 字段号来源：参考实现的 `命令实现类 = 0x50`(80)、
 * `getSystem()` 返回空 `System{}`；`protobuf 消息类` 的
 * `protobuf 编码` 里 `protobuf 编码` / `(2, status)`；
 * `设置上报回执` → `载荷类型 = 0x33`(51)。
 */
class ReportDataTest {

    @Test
    fun `常量 - 与协议规范一致`() {
        assertEquals(80, ReportDataIds.ID_REQUEST)
        assertEquals(51, ReportDataIds.FIELD_RESULT)
        assertEquals(50, ReportDataIds.FIELD_REPORT_DATA)
        assertEquals(0, ReportStatus.SUCCESS)
        assertEquals(10, ReportStatus.FAIL)
    }

    @Test
    fun `parse - 成功回包`() {
        val r = ReportDataResult.parse(payload(ReportDataType.DEVICE_LOG, ReportStatus.SUCCESS))!!
        assertEquals(ReportDataType.DEVICE_LOG, r.type)
        assertEquals(ReportStatus.SUCCESS, r.status)
        assertTrue(r.isOk)
    }

    @Test
    fun `parse - 失败与无数据都不算成功`() {
        val fail = ReportDataResult.parse(payload(ReportDataType.DEVICE_LOG, ReportStatus.FAIL))!!
        assertFalse(fail.isOk)
        val noData = ReportDataResult.parse(payload(ReportDataType.STATISTICS, ReportStatus.NO_DATA))!!
        assertEquals(ReportDataType.STATISTICS, noData.type)
        assertFalse(noData.isOk)
    }

    @Test
    fun `parse - 不是 ReportData 回包时返回 null`() {
        assertNull(ReportDataResult.parse(ByteArray(0)))
        // 空 System（我们的请求载荷）当然不是回包
        assertNull(ReportDataResult.parse(ByteArray(0)))
    }

    /** `System{ f51 = Result{ f1 type, f2 status } }` */
    private fun payload(type: Int, status: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        Pb.uint(result, 1, type.toLong())
        Pb.uint(result, 2, status.toLong())
        val system = java.io.ByteArrayOutputStream()
        Pb.bytes(system, ReportDataIds.FIELD_RESULT, result.toByteArray())
        return system.toByteArray()
    }
}
