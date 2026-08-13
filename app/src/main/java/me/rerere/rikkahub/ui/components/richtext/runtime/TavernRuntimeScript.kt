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

  // ── TH 风格事件订阅：DOM CustomEvent('th:<name>') + 宿主侧 events.subscribe 登记 ──
  var thListeners = {};
  function addThListener(name, handler){
    var listener = function(ev){ handler(ev.detail); };
    document.addEventListener('th:' + name, listener);
    (thListeners[name] = thListeners[name] || []).push({ handler: handler, listener: listener });
    // 登记宿主事件订阅（无权限时被拒，静默降级为只收脚本本地事件）
    call('events.subscribe', { name: name }).catch(function(){});
    return true;
  }
  function removeThListener(name, handler){
    var list = thListeners[name] || [];
    for (var i = list.length - 1; i >= 0; i--) {
      if (!handler || list[i].handler === handler) {
        document.removeEventListener('th:' + name, list[i].listener);
        list.splice(i, 1);
      }
    }
    if (list.length === 0) {
      call('events.unsubscribe', { name: name }).catch(function(){});
    }
    return true;
  }

  var eventSource = {
    on: function(name, handler){ addThListener(name, handler); return Promise.resolve(true); },
    off: function(name, handler){ removeThListener(name, handler); return Promise.resolve(true); },
    once: function(name, handler){
      var wrapped = function(detail){ removeThListener(name, wrapped); handler(detail); };
      addThListener(name, wrapped);
      return Promise.resolve(true);
    },
    emit: function(name, payload){ return call('events.emit', { name: name, payload: payload || null }); }
  };

  // ── SillyTavern.getContext()：宿主推送快照（th:context_updated 内部订阅，无需权限） ──
  var stContext = null;
  (function(){
    var listener = function(ev){ stContext = ev.detail; };
    document.addEventListener('th:context_updated', listener);
  })();

  window.event_types = {
    GENERATION_STARTED: 'GENERATION_STARTED',
    MESSAGE_SENT: 'MESSAGE_SENT',
    MESSAGE_RECEIVED: 'MESSAGE_RECEIVED',
    MESSAGE_EDITED: 'MESSAGE_EDITED',
    MESSAGE_DELETED: 'MESSAGE_DELETED',
    MESSAGE_SWIPED: 'MESSAGE_SWIPED',
    CHARACTER_MESSAGE_RENDERED: 'CHARACTER_MESSAGE_RENDERED',
    USER_MESSAGE_RENDERED: 'USER_MESSAGE_RENDERED',
    MESSAGE_RENDERED: 'MESSAGE_RENDERED'
  };

  window.SillyTavern = window.SillyTavern || {
    getContext: function(){ return stContext; },
    eventSource: eventSource,
    event_types: window.event_types
  };

  var api = {
    runtime: {
      ping: function(){ return call('runtime.ping', {}); }
    },
    variables: {
      get: function(key, scope){ return call('variables.get', { key: key, scope: scope || 'chat' }); },
      set: function(key, value, scope){ return call('variables.set', { key: key, value: value, scope: scope || 'chat' }); },
      list: function(scope){ return call('variables.list', { scope: scope || 'chat' }); },
      delete: function(key, scope){ return call('variables.delete', { key: key, scope: scope || 'chat' }); }
    },
    slash: {
      run: function(command, args){ return call('slash.run', { command: command, args: args || {} }); }
    },
    events: {
      on: function(name, handler){ return eventSource.on(name, handler); },
      off: function(name, handler){ return eventSource.off(name, handler); },
      emit: function(name, payload){ return eventSource.emit(name, payload); },
      subscribe: function(name){ return call('events.subscribe', { name: name }); },
      unsubscribe: function(name){ return call('events.unsubscribe', { name: name }); }
    },
    world: {
      getEntries: function(){ return call('world.getEntries', {}); },
      upsertEntry: function(entry){ return call('world.upsertEntry', { entry: entry }); },
      deleteEntry: function(id){ return call('world.deleteEntry', { id: id }); }
    },
    messages: {
      getCurrent: function(){ return call('messages.getCurrent', {}); },
      updateCurrent: function(patch){ return call('messages.updateCurrent', { patch: patch }); }
    },

    // ── TavernHelper 风格别名（委托上面的 api.variables，保持单一实现） ──
    eventSource: eventSource,
    getVariable: function(key, scope){ return api.variables.get(key, scope); },
    setVariable: function(key, value, scope){ return api.variables.set(key, value, scope); },
    deleteVariable: function(key, scope){ return api.variables.delete(key, scope); },
    getVariables: function(scope){ return api.variables.list(scope); },
    setVariables: function(vars, scope){
      var keys = Object.keys(vars || {});
      return Promise.all(keys.map(function(key){
        return api.variables.set(key, vars[key], scope);
      })).then(function(){ return true; });
    }
  };

  window.TavernHelperCompat = api;
  window.TavernHelper = window.TavernHelper || api;
  window.TH = window.TH || api;
})();
""".trimIndent()
