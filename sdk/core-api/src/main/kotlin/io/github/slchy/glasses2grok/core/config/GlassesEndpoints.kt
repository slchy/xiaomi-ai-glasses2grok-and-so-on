package io.github.slchy.glasses2grok.core.config

/** Glasses album HTTP gateway. This is not a general cloud endpoint list. */
public object GlassesEndpoints {
    public const val ALBUM_HOST: String = "192.168.43.1"
    public const val ALBUM_PORT: Int = 8080
    public const val ALBUM_PATH_FILELISTS: String = "/v1/filelists"
    public const val ALBUM_PATH_FILES: String = "/v1/files/"
}
