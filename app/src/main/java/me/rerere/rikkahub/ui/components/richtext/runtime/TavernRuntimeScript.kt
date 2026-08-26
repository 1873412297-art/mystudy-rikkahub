package me.rerere.rikkahub.ui.components.richtext.runtime

internal fun buildTavernRuntimeScript(): String = """
(function(){
  if (window.TavernHelperCompat) return;
  var seq = 0;
  function call(method, params) { return callWithId(method, params, null); }
  function callWithId(method, params, forcedId) {
    return new Promise(function(resolve, reject){
      var transport = typeof window.__RIKKAHUB_RUNTIME_CALL__ === 'function'
        ? window.__RIKKAHUB_RUNTIME_CALL__
        : (window.TavernRuntimeBridge && typeof window.TavernRuntimeBridge.call === 'function'
          ? function(request, callback){ window.TavernRuntimeBridge.call(request, callback); }
          : null);
      if (!transport) {
        reject({ code: 'BRIDGE_MISSING', message: 'TavernRuntimeBridge is not available' });
        return;
      }
      var id = forcedId || String(++seq);
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
        transport(request, cb);
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

  // ── MacroHelper：ST 兼容宏注册（fn 序列化为源码经 RPC 注册到宿主表） ──
  var macroStore = {};
  window.MacroHelper = {
    registerMacro: function(name, fn){
      if (typeof fn !== 'function') return Promise.resolve(false);
      macroStore[name] = fn;
      return call('macros.register', { name: name, source: String(fn) }).then(function(ok){
        if (!ok) { delete macroStore[name]; }
        return ok;
      }).catch(function(){ delete macroStore[name]; });
    },
    getMacro: function(name){
      if (typeof macroStore[name] === 'function') { return macroStore[name]; }
      return function(args){ return '{{' + name + '::' + (args === undefined ? '' : args) + '}}'; };
    },
    getMacros: function(){ return Object.keys(macroStore); }
  };

  // ── SlashCommandParser：ST 兼容命令注册垫片 ──
  window.SlashCommandParser = {
    'add': function(definition){
      var name = definition && definition.name;
      var callback = definition && definition.callback;
      if (typeof name !== 'string' || typeof callback !== 'function') { return Promise.resolve(false); }
      return call('slash.register', {
        name: name,
        source: String(callback),
        aliases: definition.aliases || [],
        helpString: definition.helpString || ''
      });
    }
  };

  window.SillyTavern.getRequestHeaders = function(){
    return call('requestHeaders.get', {});
  };
  window.SillyTavern.sendHook = {
    register: function(source){
      return call('sendHook.register', { source: String(source) });
    }
  };

  // ── 明确不支持的 ST 宿主功能：保持可调用，统一返回结构化 Promise rejection ──
  var unsupportedHost = {
    extensions: {
      install: function(extension){ return call('extensions.install', { extension: extension }); },
      uninstall: function(extension){ return call('extensions.uninstall', { extension: extension }); },
      update: function(extension){ return call('extensions.update', { extension: extension }); }
    },
    server: {
      getAdminStatus: function(){ return call('server.getAdminStatus', {}); },
      filesystem: {
        read: function(path){ return call('server.filesystem.read', { path: path }); }
      }
    },
    dom: {
      jquery: {
        queryTopLevel: function(selector){ return call('dom.jquery.queryTopLevel', { selector: selector }); }
      }
    },
    backend: {
      st: {
        request: function(route, options){ return call('backend.st.request', { route: route, options: options || {} }); }
      }
    }
  };
  window.SillyTavern.extensions = window.SillyTavern.extensions || unsupportedHost.extensions;
  window.SillyTavern.server = window.SillyTavern.server || unsupportedHost.server;
  window.SillyTavern.dom = window.SillyTavern.dom || unsupportedHost.dom;
  window.SillyTavern.backend = window.SillyTavern.backend || unsupportedHost.backend;
  window.RikkaHubTavern = window.RikkaHubTavern || {};
  window.RikkaHubTavern.runtime = window.RikkaHubTavern.runtime || {};

  var api = {
    runtime: {
      ping: function(){ return call('runtime.ping', {}); }
    },
    variables: {
      get: function(key, scope){ return call('variables.get', { key: key, scope: scope || 'chat' }); },
      set: function(key, value, scope){ return call('variables.set', { key: key, value: value, scope: scope || 'chat' }); },
      list: function(scope){ return call('variables.list', { scope: scope || 'chat' }); },
      delete: function(key, scope){ return call('variables.delete', { key: key, scope: scope || 'chat' }); },
      replace: function(vars, scope){ return call('variables.replace', { values: vars || {}, scope: scope || 'chat' }); },
      update: function(vars, scope){ return call('variables.update', { values: vars || {}, scope: scope || 'chat' }); }
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
      getEntries: function(book){ return call('world.getEntries', book ? { book: book } : {}); },
      upsertEntry: function(entry){ return call('world.upsertEntry', { entry: entry }); },
      deleteEntry: function(id){ return call('world.deleteEntry', { id: id }); },
      listBooks: function(){ return call('world.listBooks', {}); },
      getBook: function(book){ return call('world.getBook', { book: book }); },
      createBook: function(name, entries){ return call('world.createBook', { name: name, entries: entries || [] }); },
      updateBook: function(book, patch){ return call('world.updateBook', { book: book, patch: patch || {} }); },
      deleteBook: function(book){ return call('world.deleteBook', { book: book }); }
    },
    messages: {
      list: function(){ return call('messages.list', {}); },
      get: function(id){ return call('messages.get', { id: id }); },
      getCurrent: function(){ return call('messages.getCurrent', {}); },
      updateCurrent: function(patch){ return call('messages.updateCurrent', { patch: patch }); },
      getChatMessages: function(range, options){
        return call('messages.getChatMessages', { range: String(range), options: options || {} });
      },
      setChatMessage: function(fieldValues, messageId, options){
        return call('messages.setChatMessage', {
          field_values: typeof fieldValues === 'string' ? { message: fieldValues } : (fieldValues || {}),
          message_id: Number(messageId),
          options: options || {}
        });
      },
      setChatMessages: function(messages, options){
        return call('messages.setChatMessages', { messages: messages || [], options: options || {} });
      },
      create: function(role, text){ return call('messages.create', { role: role, text: text }); },
      update: function(id, text){ return call('messages.update', { id: id, text: text }); },
      delete: function(id){ return call('messages.delete', { id: id }); }
    },
    generation: {
      // options: { prompt | messages, useChat?, temperature?, maxTokens?, requestId? }
      // requestId 用于 generation.cancel(id) 取消单次生成；缺省自动生成
      generate: function(options){
        options = options || {};
        return callWithId('generation.generate', options, options.requestId ? String(options.requestId) : null);
      },
      generateRaw: function(options){
        options = options || {};
        return callWithId('generation.generateRaw', options, options.requestId ? String(options.requestId) : null);
      },
      cancel: function(id){ return call('generation.cancel', { id: id }); },
      cancelAll: function(){ return call('generation.cancelAll', {}); }
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
    },
    replaceVariables: function(vars, scope){
      return api.variables.replace(vars, scope);
    },
    updateVariablesWith: function(updater, scope){
      return api.variables.list(scope).then(function(current){
        var updated = updater(current) || current;
        return api.variables.replace(updated, scope);
      });
    },
    getWorldbookNames: function(){
      return api.world.listBooks().then(function(books){
        return books.map(function(book){ return book.name; });
      });
    },
    getWorldbook: function(name){
      return api.world.getBook(name).then(function(book){ return book.entries || []; });
    },
    createWorldbook: function(name, entries){
      return api.world.createBook(name, entries || []).then(function(){ return true; });
    },
    replaceWorldbook: function(name, entries){
      return api.world.updateBook(name, { entries: entries || [] }).then(function(){ return true; });
    },
    updateWorldbookWith: function(name, updater){
      return api.world.getBook(name).then(function(book){
        var updated = updater(book.entries || []);
        return api.world.updateBook(name, { entries: updated || [] }).then(function(){ return updated || []; });
      });
    },
    deleteWorldbook: function(name){
      return api.world.deleteBook(name);
    },
    generate: function(options){
      return api.generation.generate(options).then(function(result){ return result.text; });
    },
    generateRaw: function(options){
      return api.generation.generateRaw(options).then(function(result){ return result.text; });
    }
  };

  // ── MVU / Tavern Helper globals used by imported visual status templates ──
  // Keep a synchronous snapshot because these templates render through _.get(getAllVariables()).
  var cachedCompatVariables = {};
  function refreshCompatVariables(){
    return api.variables.list('chat').then(function(value){
      cachedCompatVariables = value && typeof value === 'object' ? value : {};
      return cachedCompatVariables;
    }).catch(function(){ return cachedCompatVariables; });
  }
  window.getAllVariables = function(){
    var variables = stContext && stContext.variables && typeof stContext.variables === 'object'
      ? stContext.variables
      : cachedCompatVariables;
    return variables && variables.stat_data ? variables : { stat_data: variables || {} };
  };
  window._ = window._ || {};
  if (typeof window._.get !== 'function') {
    window._.get = function(source, path, fallback){
      var parts = Array.isArray(path) ? path : String(path || '').split('.');
      var value = source;
      for (var i = 0; i < parts.length; i++) {
        if (value == null || !Object.prototype.hasOwnProperty.call(Object(value), parts[i])) return fallback;
        value = value[parts[i]];
      }
      return value === undefined ? fallback : value;
    };
  }
  window.Mvu = window.Mvu || { events: { VARIABLE_UPDATE_ENDED: 'VARIABLE_UPDATE_ENDED' } };
  window.waitGlobalInitialized = function(name){
    return name === 'Mvu' ? refreshCompatVariables() : Promise.resolve(window[name]);
  };
  window.errorCatched = function(fn){
    return function(){
      try {
        var result = fn.apply(this, arguments);
        if (result && typeof result.catch === 'function') result.catch(function(error){ console.error(error); });
        return result;
      } catch (error) {
        console.error(error);
      }
    };
  };
  window.eventOn = function(name, handler){
    if (name === window.Mvu.events.VARIABLE_UPDATE_ENDED) {
      document.addEventListener('th:context_updated', function(){
        refreshCompatVariables().then(handler);
      });
      return true;
    }
    return eventSource.on(name, handler);
  };

  window.TavernHelperCompat = api;
  window.TavernHelper = window.TavernHelper || api;
  window.TH = window.TH || api;
  window.getChatMessages = api.messages.getChatMessages;
  window.setChatMessage = api.messages.setChatMessage;
  window.setChatMessages = api.messages.setChatMessages;
  window.RikkaHubTavern.runtime.api = api;
})();
""".trimIndent()
