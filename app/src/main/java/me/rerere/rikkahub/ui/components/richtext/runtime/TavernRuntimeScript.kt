package me.rerere.rikkahub.ui.components.richtext.runtime

internal fun buildTavernRuntimeScript(): String = """
(function(){
  if (window.TavernHelperCompat) return;
  var seq = 0;
  function call(method, params) {
    return new Promise(function(resolve, reject){
      if (!window.TavernRuntimeBridge || typeof window.TavernRuntimeBridge.call !== 'function') {
        reject({ code: 'BRIDGE_MISSING', message: 'TavernRuntimeBridge is not available' });
        return;
      }
      var id = String(++seq);
      var cb = '__rikkahubTavernRuntimeCallback_' + id;
      window[cb] = function(response){
        try {
          delete window[cb];
          if (response && response.ok) resolve(response.result);
          else reject(response && response.error ? response.error : { code: 'UNKNOWN', message: 'Runtime call failed' });
        } catch (e) {
          reject({ code: 'CALLBACK_ERROR', message: String(e && e.message || e) });
        }
      };
      try {
        var request = JSON.stringify({ id: id, method: method, params: params || {} });
        window.TavernRuntimeBridge.call(request, cb);
      } catch (e) {
        delete window[cb];
        reject({ code: 'BRIDGE_ERROR', message: String(e && e.message || e) });
      }
    });
  }

  var api = {
    runtime: {
      ping: function(){ return call('runtime.ping', {}); }
    },
    variables: {
      get: function(key, scope){ return call('variables.get', { key: key, scope: scope || 'chat' }); },
      set: function(key, value, scope){ return call('variables.set', { key: key, value: value, scope: scope || 'chat' }); },
      list: function(scope){ return call('variables.list', { scope: scope || 'chat' }); }
    },
    slash: {
      run: function(command, args){ return call('slash.run', { command: command, args: args || {} }); }
    },
    events: {
      on: function(name, handler){
        document.addEventListener('th:' + name, function(ev){ handler(ev.detail); });
        return Promise.resolve(true);
      },
      emit: function(name, payload){ return call('events.emit', { name: name, payload: payload || null }); }
    },
    world: {
      getEntries: function(){ return call('world.getEntries', {}); },
      upsertEntry: function(entry){ return call('world.upsertEntry', { entry: entry }); },
      deleteEntry: function(id){ return call('world.deleteEntry', { id: id }); }
    },
    messages: {
      getCurrent: function(){ return call('messages.getCurrent', {}); },
      updateCurrent: function(patch){ return call('messages.updateCurrent', { patch: patch }); }
    }
  };

  window.TavernHelperCompat = api;
  window.TavernHelper = window.TavernHelper || api;
  window.TH = window.TH || api;
})();
""".trimIndent()
