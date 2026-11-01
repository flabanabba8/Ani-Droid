/* Tizen entry for the shared webOS service (service.js). Node 4.4 / ES5.
   Maps webos-service register/respond onto message ports and imports a connection
   pushed by tizen-tv/provision.sh. */
'use strict';
var fs = require('fs');
var Module = require('module');
var app = tizen.application.getCurrentApplication().appInfo;
var uiId = app.packageId + '.app';
var pushedPath = '/home/owner/share/tmp/sdk_tools/anidroid-connection.json';
var handlers = {};

process.env.ANI_DATA_DIR = '/opt/usr/home/owner/apps_rw/' + app.packageId + '/data';
function Service() {}
Service.prototype.register = function (name, fn) { handlers[name] = fn; };
var load = Module._load;
Module._load = function (request) { return request === 'webos-service' ? Service : load.apply(this, arguments); };
require('./service.js');

// Trusted ports: only apps signed with our certificate can talk. Ports reject messages over ~8 KB (measured on Tizen 5.5), so JSON goes in chunks.
var CHUNK = 2000, parts = {};
function toUi(key, value) {
    try { tizen.messageport.requestTrustedRemoteMessagePort(uiId, 'AniUI').sendMessage([{key: key, value: value}]); } catch (e) {}
}
function reply(id, response) {
    var s = JSON.stringify({id: id, response: response}), n = Math.ceil(s.length / CHUNK) || 1;
    for (var i = 0; i < n; i++) toUi('res', id + '|' + i + '|' + n + '|' + s.substr(i * CHUNK, CHUNK));
}
function call(method, payload, respond) {
    if (!handlers[method]) return respond({returnValue: false, errorText: 'Unsupported request.'});
    handlers[method]({payload: payload, respond: respond});
}
function importPushed(next) {
    var text;
    try { text = fs.readFileSync(pushedPath, 'utf8'); } catch (e) { return next(); }
    try { fs.unlinkSync(pushedPath); } catch (e) {} // sdk_tools is world-readable; never leave the key there
    var payload; try { payload = JSON.parse(text); } catch (e) { return next(); }
    call('configure', payload, function () { next(); });
}

tizen.messageport.requestTrustedLocalMessagePort('AniSvc').addMessagePortListener(function (data) {
    var value = String(data[0].value), a = value.split('|', 3), id = a[0], buf = parts[id] || (parts[id] = []);
    if (a.length < 3 || +a[2] > 2000) return delete parts[id]; // 2000 chunks = 4 MB, above any valid request
    buf[+a[1]] = value.slice(a[0].length + a[1].length + a[2].length + 3);
    if (buf.filter(String).length < +a[2]) return;
    delete parts[id];
    var m; try { m = JSON.parse(buf.join('')); } catch (e) { return; }
    call(m.method, m.payload || {}, function (response) { reply(m.id, response); });
});

module.exports.onStart = function () { importPushed(function () { toUi('ready', '1'); }); };
module.exports.onRequest = function () { importPushed(function () { toUi('ready', '1'); }); };
module.exports.onExit = function () {};
