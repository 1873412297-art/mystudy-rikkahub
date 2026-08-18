package me.rerere.rikkahub.data.ai.slash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent variable store for slash command scripts.
 * Each script gets its own namespace. Variables persist across invocations.
 *
 * Mirrors JS-Slash-Runner's variable system: scripts can get/set/delete variables.
 */
class ScriptVariableStore(context: Context) {

    private val storeFile = File(context.filesDir, "slash-variables.json")
    private val stores = ConcurrentHashMap<String, MutableMap<String, String>>()

    init {
        load()
    }

    /** Get all variables for a script namespace */
    fun getAll(scriptName: String): Map<String, String> {
        return stores.getOrPut(scriptName) { mutableMapOf() }.toMap()
    }

    /** Get a single variable */
    fun get(scriptName: String, key: String): String? {
        return stores[scriptName]?.get(key)
    }

    /** Set a variable */
    fun set(scriptName: String, key: String, value: String) {
        stores.getOrPut(scriptName) { mutableMapOf() }[key] = value
        save()
    }

    /** Delete a variable */
    fun delete(scriptName: String, key: String) {
        stores[scriptName]?.remove(key)
        save()
    }

    /** Replace all variables for a script */
    fun replaceAll(scriptName: String, vars: Map<String, String>) {
        stores[scriptName] = vars.toMutableMap()
        save()
    }

    /** Clear all variables for a script */
    fun clearAll(scriptName: String) {
        stores.remove(scriptName)
        save()
    }

    /** Build a JavaScript object literal string from stored variables */
    fun toJsObject(scriptName: String): String {
        val vars = stores[scriptName] ?: return "{}"
        return vars.entries.joinToString(",", "{", "}") { (k, v) ->
            """"${escapeJs(k)}":"${escapeJs(v)}""""
        }
    }

    private fun load() {
        try {
            if (storeFile.exists()) {
                val json = JSONObject(storeFile.readText())
                json.keys().forEach { scriptName ->
                    val scriptVars = json.getJSONObject(scriptName)
                    val map = mutableMapOf<String, String>()
                    scriptVars.keys().forEach { key ->
                        map[key] = scriptVars.getString(key)
                    }
                    stores[scriptName] = map
                }
            }
        } catch (e: Exception) {
            Log.w("ScriptVarStore", "Failed to load variables", e)
        }
    }

    private fun save() {
        try {
            val json = JSONObject()
            stores.forEach { (scriptName, vars) ->
                val scriptJson = JSONObject()
                vars.forEach { (k, v) -> scriptJson.put(k, v) }
                json.put(scriptName, scriptJson)
            }
            storeFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w("ScriptVarStore", "Failed to save variables", e)
        }
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
    }
}
