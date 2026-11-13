package io.github.slchy.glasses2grok.core.query

import io.github.slchy.glasses2grok.core.pb.Pb

/**
 * `System`(2) **id=80 `ReportData`**：基础状态上报（设备日志 / 统计）。
 *
 * 参考实现：
 * - `命令实现类 = 0x50`(80)，`getSystem()` 返回**空** `System{}`
 * - 回包 `System.f51 = ReportData.Result{ f1 type, f2 status }`
 * - 参考实现每 **1 小时**触发一次
 *
 * `System.f50 = ReportData{ f1 type, f2 id, f3 repeated Part{ f1 type, f2 url } }`
 * 是手机侧可选的详细载荷（`Part.url` 用来指定上传地址）；我们只发空载荷，
 * 设备照样会回 `Result`（真机 2026-11-11）。
 */
public object ReportDataIds {
    public const val ID_REQUEST: Int = 80

    /** `System.f51`（`设置上报回执` → 51）：`id=80` 的回包。 */
    public const val FIELD_RESULT: Int = 51

    /** `System.f50`（`设置上报` → 50）：详细上报载荷。 */
    public const val FIELD_REPORT_DATA: Int = 50
}

/** `ReportData.type` / `ReportData.Result.type`。 */
internal object ReportDataType {
    public const val DEVICE_LOG: Int = 1
    public const val STATISTICS: Int = 2
    public const val DUMP_LOG: Int = 3
}

/** `ReportData.Result.status`。 */
internal object ReportStatus {
    public const val SUCCESS: Int = 0
    public const val NO_DATA: Int = 1
    public const val URL_DIRECT: Int = 2
    public const val BUSY: Int = 3
    public const val FAIL: Int = 10
}

/** `id=80` 的回包内容（`ReportData.Result`）。 */
public data class ReportDataResult(val type: Int, val status: Int) {
    val isOk: Boolean get() = status == ReportStatus.SUCCESS

    public companion object {
        /** 解析 `System{ f51 = ReportData.Result{ f1 type, f2 status } }`。 */
        public fun parse(payload: ByteArray): ReportDataResult? {
            val body = Pb.fields(payload)[ReportDataIds.FIELD_RESULT] ?: return null
            val f = Pb.fields(body)
            return ReportDataResult(
                type = (f[1]?.let { Pb.varintValue(it) } ?: 0L).toInt(),
                status = (f[2]?.let { Pb.varintValue(it) } ?: 0L).toInt(),
            )
        }
    }
}
