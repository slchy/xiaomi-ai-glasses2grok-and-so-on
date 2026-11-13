package io.github.slchy.glasses2grok.core.media

public object MediaFormat {
    public const val JPEG: Int = 0
    public const val PNG: Int = 1
    public const val MP4: Int = 2
    public const val QUICKTIME: Int = 3
    public const val PCM: Int = 4
    public const val OPUS: Int = 5
    public const val SBC: Int = 6
    public const val MSBC: Int = 7
    public const val YUV_META: Int = 8
    public const val HEIC_META: Int = 9
    public const val MP4_META: Int = 10
    public const val OPUS_IMAGE: Int = 11

    public fun name(value: Int): String = when (value) {
        JPEG -> "JPEG"
        PNG -> "PNG"
        MP4 -> "MP4"
        QUICKTIME -> "QUICKTIME"
        PCM -> "PCM"
        OPUS -> "OPUS"
        SBC -> "SBC"
        MSBC -> "MSBC"
        YUV_META -> "YUV_META"
        HEIC_META -> "HEIC_META"
        MP4_META -> "MP4_META"
        OPUS_IMAGE -> "OPUS_IMAGE"
        else -> "UNKNOWN($value)"
    }
}
