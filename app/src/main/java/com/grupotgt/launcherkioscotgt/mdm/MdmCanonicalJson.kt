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
        ) { key -> "${JSONObject.quote(key)}:${stringify(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { index -> stringify(value.opt(index)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
