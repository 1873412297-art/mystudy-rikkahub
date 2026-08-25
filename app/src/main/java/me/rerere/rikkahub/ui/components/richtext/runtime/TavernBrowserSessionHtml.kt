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
            window.name='TH-script--rikkahub--'+meta.id;
            function clone(value){return value===undefined?undefined:JSON.parse(JSON.stringify(value));}
            function emitLifecycle(name){
              document.dispatchEvent(new CustomEvent('th:'+name,{detail:{script_id:meta.id},bubbles:true}));
              if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.lifecycle(name.toLowerCase().replace('script_','').replace('app_ready','running'),'');
            }
            function captureConsole(level,original){
              return function(){
                original.apply(console,arguments);
                try{if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.log(level,Array.prototype.map.call(arguments,function(item){
                  return typeof item==='string'?item:JSON.stringify(item);
                }).join(' '));}catch(ignored){}
              };
            }
            console.debug=captureConsole('debug',console.debug);
            console.info=captureConsole('info',console.info);
            console.warn=captureConsole('warn',console.warn);
            console.error=captureConsole('error',console.error);
            window.addEventListener('error',function(event){
              if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.lifecycle('runtime_crash',event.message||'未捕获脚本错误');
            });
            window.addEventListener('unhandledrejection',function(event){
              if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.lifecycle('runtime_crash',String(event.reason||'未处理 Promise 拒绝'));
            });
            window.getScriptId=function(){return meta.id;};
            window.getScriptName=function(){return meta.name;};
            window.getScriptInfo=function(){return meta.info;};
            window.getIframeName=function(){return window.name;};
            window.reloadIframe=function(){window.location.reload();};
            window.getScriptButtons=function(){return JSON.parse(JSON.stringify(meta.buttons||[]));};
            window.replaceScriptButtons=function(buttons){
              meta.buttons=clone(buttons||[]);
              if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.replaceButtons(JSON.stringify(meta.buttons));
              return window.getScriptButtons();
            };
            window.updateScriptButtonsWith=function(updater){
              var result=updater(window.getScriptButtons());
              if(result&&typeof result.then==='function')return result.then(window.replaceScriptButtons);
              return window.replaceScriptButtons(result);
            };
            window.appendInexistentScriptButtons=function(buttons){
              return window.updateScriptButtonsWith(function(current){
                return current.concat((buttons||[]).filter(function(item){
                  return !current.some(function(old){return old.name===item.name;});
                }));
              });
            };
            window.getButtonEvent=function(name){return 'rikkahub_script_button:'+meta.id+':'+encodeURIComponent(String(name));};
            window.eventOn=function(name,handler){return window.SillyTavern.eventSource.on(name,handler);};
            window.eventOnce=function(name,handler){return window.SillyTavern.eventSource.once(name,handler);};
            window.eventEmit=function(name,payload){return window.SillyTavern.eventSource.emit(name,payload);};
            var hostGetVariables=window.getVariables;
            var hostReplaceVariables=window.replaceVariables;
            window.getVariables=function(option){
              if(!option||option.type==='script')return clone(meta.data||{});
              return hostGetVariables?hostGetVariables(option):{};
            };
            window.replaceVariables=function(value,option){
              if(!option||option.type==='script'){
                meta.data=clone(value||{});
                if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.replaceData(JSON.stringify(meta.data));
                return clone(meta.data);
              }
              return hostReplaceVariables?hostReplaceVariables(value,option):undefined;
            };
            window.updateVariablesWith=function(updater,option){
              var result=updater(window.getVariables(option));
              if(result&&typeof result.then==='function')return result.then(function(value){
                window.replaceVariables(value,option);return value;
              });
              window.replaceVariables(result,option);return result;
            };
            window.insertOrAssignVariables=function(value,option){
              return window.updateVariablesWith(function(old){return Object.assign(old,value||{});},option);
            };
            window.insertVariables=function(value,option){
              return window.updateVariablesWith(function(old){return Object.assign({},value||{},old);},option);
            };
            window.deleteVariable=function(path,option){
              var deleted=false;
              var variables=window.updateVariablesWith(function(old){
                var keys=String(path).split('.');var target=old;
                for(var i=0;i<keys.length-1;i++){if(!target||typeof target!=='object')return old;target=target[keys[i]];}
                if(target&&Object.prototype.hasOwnProperty.call(target,keys[keys.length-1])){
                  delete target[keys[keys.length-1]];deleted=true;
                }
                return old;
              },option);
              return {variables:variables,delete_occurred:deleted};
            };
            window.TavernHelper=window.TH=Object.assign(window.TH||{}, {
              getScriptId:window.getScriptId,getScriptName:window.getScriptName,getScriptInfo:window.getScriptInfo,
              getIframeName:window.getIframeName,reloadIframe:window.reloadIframe,
              getScriptButtons:window.getScriptButtons,replaceScriptButtons:window.replaceScriptButtons,
              updateScriptButtonsWith:window.updateScriptButtonsWith,getButtonEvent:window.getButtonEvent,
              getVariables:window.getVariables,replaceVariables:window.replaceVariables,
              updateVariablesWith:window.updateVariablesWith,insertVariables:window.insertVariables,
              insertOrAssignVariables:window.insertOrAssignVariables,deleteVariable:window.deleteVariable
            });
            window.addEventListener('pagehide',function(){
              if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.lifecycle('paused','');
              emitLifecycle('SCRIPT_UNLOADING');
            },{once:true});
            setTimeout(function(){
              try{
                emitLifecycle('SCRIPT_LOADING');
                (0,eval)(decode('$source'));
                emitLifecycle('SCRIPT_LOADED');
                emitLifecycle('APP_READY');
              }
              catch(error){
                if(window.RikkahubScriptBridge)window.RikkahubScriptBridge.lifecycle('runtime_crash',String(error));
                console.error('[酒馆助手]['+meta.name+']',error);
              }
            },0);
          },{once:true});
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

private fun String.toBase64(): String = Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
