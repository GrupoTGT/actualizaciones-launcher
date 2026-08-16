package com.grupotgt.launcherkioscotgt.mdm

import org.json.JSONArray
import org.json.JSONObject

internal object MdmCanonicalJson {
    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { key -> "${quote(key)}:${stringify(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { index -> stringify(value.opt(index)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    char.code < 0x20 -> append("\\u%04x".format(char.code))
                    char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                        append(char)
                        append(value[++index])
                    }
                    char.isSurrogate() -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            index += 1
        }
        append('"')
    }
}
