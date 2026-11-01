/* Gives the shared webOS app the two webOS calls it uses, on Tizen: webOS.service.request
   (sent to the bundled Tizen service over message ports) and webOS.platformBack. ES5 for Chromium 69. */
(function () {
    var pkg = tizen.application.getCurrentApplication().appInfo.packageId;
    var pending = {}, queue = [], nextId = 1, remote = null, failed = null;
    // Trusted ports: only apps signed with our certificate can talk. Ports reject messages over ~8 KB (measured on Tizen 5.5), so JSON goes in chunks.
    var CHUNK = 2000, parts = {};
    function send(msg) {
        var s = JSON.stringify(msg), n = Math.ceil(s.length / CHUNK) || 1;
        for (var i = 0; i < n; i++) remote.sendMessage([{key: 'req', value: msg.id + '|' + i + '|' + n + '|' + s.substr(i * CHUNK, CHUNK)}]);
    }
    function receive(value) {
        var a = value.split('|', 3), id = a[0], buf = parts[id] || (parts[id] = []);
        buf[+a[1]] = value.slice(a[0].length + a[1].length + a[2].length + 3);
        if (buf.filter(String).length < +a[2]) return;
        delete parts[id]; var m = JSON.parse(buf.join('')); done(m.id, m.response);
    }
    function failAll(text) { failed = text; queue.forEach(function (m) { done(m.id, {returnValue: false, errorText: text}); }); queue = []; }
    function done(id, r) {
        var p = pending[id]; if (!p) return; delete pending[id];
        if (r.returnValue) { if (p.onSuccess) p.onSuccess(r); } else if (p.onFailure) p.onFailure(r);
    }
    tizen.messageport.requestTrustedLocalMessagePort('AniUI').addMessagePortListener(function (data) {
        var item = data[0] || {};
        if (item.key === 'ready') {
            try { remote = tizen.messageport.requestTrustedRemoteMessagePort(pkg + '.service', 'AniSvc'); } catch (e) { return failAll('Catalog service unavailable.'); }
            queue.forEach(send); queue = [];
        } else if (item.key === 'res') receive(item.value);
    });
    tizen.application.launchAppControl(new tizen.ApplicationControl('http://tizen.org/appcontrol/operation/service'), pkg + '.service',
        null, function () { failAll('Catalog service could not start.'); });
    ['MediaPlay', 'MediaPause', 'MediaPlayPause'].forEach(function (k) { try { tizen.tvinputdevice.registerKey(k); } catch (e) {} });
    window.webOS = {
        platformBack: function () { tizen.application.getCurrentApplication().exit(); },
        service: {request: function (uri, o) {
            var id = nextId++, msg = {id: id, method: o.method, payload: o.parameters || {}};
            pending[id] = o;
            if (failed) return done(id, {returnValue: false, errorText: failed});
            if (remote) send(msg); else queue.push(msg);
        }}
    };
})();
