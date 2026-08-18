package me.rerere.rikkahub.data.ai.slash

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Central manager for the JS-Slash-Runner integration.
 * Ties together script storage, variable store, and execution engine.
 */
class ScriptManager(
    context: Context,
    settingsStore: SettingsStore,
) : SlashScriptSource {
    val variableStore = ScriptVariableStore(context)
    val engine = SlashScriptEngine(settingsStore, variableStore)

    private val scriptsDir = java.io.File(context.filesDir, "skills/slash-scripts").also {
        if (!it.exists()) it.mkdirs()
        maybeCreateExample(it)
    }

    /** List all script files */
    override fun listScripts(): List<SlashScript> {
        return scriptsDir.listFiles()
            ?.filter { it.extension == "js" }
            ?.mapNotNull { f ->
                try {
                    val commands = engine.extractCommands(f.readText())
                    SlashScript(
                        name = f.nameWithoutExtension,
                        description = commands.joinToString(", ") { "/${it.command}" },
                        source = f.readText(),
                        enabled = true,
                    )
                } catch (e: Exception) {
                    SlashScript(name = f.nameWithoutExtension, source = "", enabled = false)
                }
            }
            ?: emptyList()
    }

    /** Get script source by name */
    fun getScript(name: String): String? {
        return try {
            java.io.File(scriptsDir, "$name.js").takeIf { it.exists() }?.readText()
        } catch (e: Exception) { null }
    }

    /** Save (create or update) a script */
    fun saveScript(name: String, source: String): Boolean {
        return try {
            java.io.File(scriptsDir, "$name.js").writeText(source)
            true
        } catch (e: Exception) {
            Log.w("ScriptManager", "Failed to save $name", e)
            false
        }
    }

    /** Delete a script */
    fun deleteScript(name: String): Boolean {
        val deleted = java.io.File(scriptsDir, "$name.js").delete()
        if (deleted) variableStore.clearAll(name)
        return deleted
    }

    /** Toggle script enabled state (renames to .js.disabled / back to .js) */
    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val file = java.io.File(scriptsDir, "$name.js")
        val disabledFile = java.io.File(scriptsDir, "$name.js.disabled")
        return if (enabled) disabledFile.renameTo(file) else file.renameTo(disabledFile)
    }

    override fun extractCommands(source: String): List<SlashCommand> = engine.extractCommands(source)

    override suspend fun execute(source: String, args: String, context: SlashContext): Result<Map<String, String>> =
        engine.execute(source, args, context)

    override fun variableAccessor(scriptName: String): ScriptVariableAccessor =
        ScriptVariableStoreAccessor(scriptName, variableStore)

    private fun maybeCreateExample(dir: java.io.File) {
        if (dir.listFiles()?.any { it.extension == "js" } == true) return
        val example = java.io.File(dir, "example.js")
        try {
            example.writeText("""
function registerCommands(){return [{command:"roll",description:"Roll dice",argsHint:"<NdS>"},{command:"help",description:"Show help"},{command:"stats",description:"Show stats card"},{command:"remember",description:"Save a variable",argsHint:"<key> <value>"},{command:"recall",description:"Recall a variable",argsHint:"<key>"}];}
function handleSlash(a,c){var m=a.match(/(\\d+)d(\\d+)/);if(m){var n=Math.min(parseInt(m[1]),100);var s=Math.min(parseInt(m[2]),1000);var ro=[];var t=0;for(var i=0;i<n;i++){var v=Math.floor(Math.random()*s)+1;ro.push(v);t+=v}return{result:"Rolled "+n+"d"+s+": "+ro.join(", ")+" = "+t}};var p=a.split(" ");var cmd=p[0];var rest=p.slice(1).join(" ");if(cmd==="stats"){return{result:"Showing stats",html:'<div style="background:var(--surface);border-radius:12px;padding:14px;border:1px solid var(--outline-variant)"><h3 style="margin:0 0 10px;color:var(--primary)">'+c.char.name+'</h3><table style="width:100%"><tr><td>User</td><td style="font-weight:600">'+c.user.name+'</td></tr><tr><td>Messages</td><td style="font-weight:600">'+c.chat.messageCount+'</td></tr></table></div>'}};if(cmd==="remember"&&p.length>=3){c.variables.set(p[1],rest);return{result:"Saved: "+p[1]+" = "+rest}};if(cmd==="recall"&&p.length>=2){var v=c.variables.get(p[1]);return{result:v?""+p[1]+" = "+v:"Not found: "+p[1]}};if(cmd==="forget"&&p.length>=2){c.variables.delete(p[1]);return{result:"Deleted: "+p[1]}};return{result:"Commands: /roll NdS, /stats, /remember k v, /recall k, /forget k, /help\\nChar: "+(c.char.name||"?")+"\\nUser: "+(c.user.name||"?")};}
""".trimIndent())
        } catch (e: Exception) {
            Log.w("ScriptManager", "Failed to create example", e)
        }
    }
}
