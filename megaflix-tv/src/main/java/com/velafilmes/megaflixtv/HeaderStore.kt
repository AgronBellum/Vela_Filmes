package com.velafilmes.megaflixtv

import org.json.JSONObject

object HeaderStore {
    @Volatile
    var headers: Map<String, String> = emptyMap()

    fun update(json: String?) {
        headers = parse(json)
    }

    fun parse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
