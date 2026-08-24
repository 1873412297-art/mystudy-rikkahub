package me.rerere.rikkahub.ui.components.richtext.runtime

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript

internal fun buildTavernBrowserSessionHtml(script: TavernHelperScript): String {
    val metadata = JsonObject(
        mapOf(
            "id" to JsonPrimitive(script.id),
            "name" to JsonPrimitive(script.name),
            "info" to JsonPrimitive(script.info),
            "buttons" to JsonArray(script.button.buttons.map { button ->
                JsonObject(mapOf("name" to JsonPrimitive(button.name), "visible" to JsonPrimitive(button.visible)))
            }),
            "data" to script.data,
        ),
    ).toString().toBase64()
    val source = script.content.toBase64()
    val safeId = script.id
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="UTF-8">
          <meta name="tavern-helper-script-id" content="$safeId">
        </head>
        <body>
        <script>
        (function(){
          function decode(value){
            var bytes=Uint8Array.from(atob(value),function(c){return c.charCodeAt(0);});
            return new TextDecoder('utf-8').decode(bytes);
          }
          window.addEventListener('load',function(){
            var meta=JSON.parse(decode('$metadata'));
            window.__tavernHelperScript=meta;
            window.getScriptId=function(){return meta.id;};
            window.getScriptName=function(){return meta.name;};
            window.getScriptInfo=function(){return meta.info;};
            window.getScriptButtons=function(){return JSON.parse(JSON.stringify(meta.buttons||[]));};
            window.getButtonEvent=function(name){return 'rikkahub_script_button:'+meta.id+':'+encodeURIComponent(String(name));};
            window.eventOn=function(name,handler){return window.SillyTavern.eventSource.on(name,handler);};
            window.eventOnce=function(name,handler){return window.SillyTavern.eventSource.once(name,handler);};
            window.eventEmit=function(name,payload){return window.SillyTavern.eventSource.emit(name,payload);};
            setTimeout(function(){
              try{(0,eval)(decode('$source'));}
              catch(error){console.error('[酒馆助手]['+meta.name+']',error);}
            },0);
          },{once:true});
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

private fun String.toBase64(): String = Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
