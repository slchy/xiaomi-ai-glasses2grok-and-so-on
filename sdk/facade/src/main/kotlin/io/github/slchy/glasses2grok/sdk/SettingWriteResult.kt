package io.github.slchy.glasses2grok.sdk

/**
 * 写设置的结果。
 *
 * 与 [SendResult] 分开：写设置是**阻塞**往返（发 → 等回执 → 读回校验），
 * 成功与否取决于设备有没有真的采纳，`Queued` 那种「已入队」语义在这里没有意义。
 */
public sealed interface SettingWriteResult {
    /**
     * 写成功。
     *
     * @param segment 段号（`SystemSetting` 字段号）
     * @param value 该段该子字段**读回**的值（不是发出去的值）；
     *   [GlassesSession.writeSystemSetting] 这种整段裸写不读回，此处为 `null`
     */
    public data class Ok(public val segment: Int, public val value: Long?) : SettingWriteResult

    public data class Failed(public val reason: String) : SettingWriteResult
}
