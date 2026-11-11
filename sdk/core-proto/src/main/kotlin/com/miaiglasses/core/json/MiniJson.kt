package com.miaiglasses.core.json

/**
 * 极小的 JSON 读解析器，只为眼镜 HTTP API 的那两种载荷服务
 * （`/v1/filelists` 与 `/v1/filelists/<stem>`）。
 *
 * 为什么不引依赖：`:core` 是纯 JVM、零依赖（protobuf 也是自己写的 `Pb`），
 * 而 `org.json` 只在 Android 里可用、在 JVM 单测里是空壳。这里只需要读，
 * 不支持注释/尾逗号，遇到非法输入抛 [IllegalArgumentException]。
 *
 * 返回值约定：对象 → `Map<String, Any?>`，数组 → `List<Any?>`，字符串 → [String]，
 * 数字 → [Long] / [Double]（能整就整），布尔 → [Boolean]，`null` → `null`。
 */
public object MiniJson {

    public fun parse(text: String): Any? {
        val p = Cursor(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.eof()) { "JSON 尾部有多余内容 @${p.i}" }
        return v
    }

    public fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?>
            ?: throw IllegalArgumentException("不是 JSON 对象")

    public fun parseArray(text: String): List<Any?> =
        parse(text) as? List<Any?>
            ?: throw IllegalArgumentException("不是 JSON 数组")

    private class Cursor(val s: String) {
        var i = 0

        fun eof(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            require(i < s.length && s[i] == c) { "期望 '$c' @$i" }
            i++
        }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "意外的 EOF" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                out[k] = value()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("对象里期望 ',' 或 '}' @$i")
                }
            }
        }

        fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("数组里期望 ',' 或 ']' @$i")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "字符串未闭合" }
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        require(i < s.length) { "转义未闭合" }
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 < s.length) { "\\u 需要 4 位十六进制" }
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var isDouble = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) { i++; continue }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isDouble = true; i++; continue }
                break
            }
            val raw = s.substring(start, i)
            require(raw.isNotEmpty()) { "非法数字 @$start" }
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        fun <T> lit(word: String, v: T): T {
            require(s.startsWith(word, i)) { "期望 '$word' @$i" }
            i += word.length
            return v
        }
    }
}

/** 取值便捷函数：缺失或类型不符一律返回 null。 */
public fun Map<String, Any?>.str(key: String): String? = this[key] as? String

public fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

public fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()
