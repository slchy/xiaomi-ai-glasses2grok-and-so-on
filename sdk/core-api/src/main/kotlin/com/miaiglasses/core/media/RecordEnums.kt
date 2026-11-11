package com.miaiglasses.core.media

/**
 * `Media id=20 RequestRecord` 的类型化入参。
 *
 * 与 [RecordType] / [RecordAction] 的关系：那两个 `object` 是**线上常量表**（编解码直接用），
 * 这里是给宿主用的枚举 —— `wire` 就是常量值，两者不会漂：枚举直接引用常量。
 */

/** 录音类型（`RecordType`）。 */
public enum class RecordKind(public val wire: Int) {
    /** 普通录音。 */
    NORMAL(RecordType.NORMAL),

    /** 实时转写。 */
    REALTIME(RecordType.REALTIME),

    /** 听歌识曲。 */
    MUSIC(RecordType.MUSIC),
}

/** 录音动作（`RecordAction`）。 */
public enum class RecordCommand(public val wire: Int) {
    /** 开始。 */
    START(RecordAction.START),

    /** 停止。 */
    STOP(RecordAction.STOP),
}
