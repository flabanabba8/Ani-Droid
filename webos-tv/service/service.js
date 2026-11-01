/* Compatible with webOS Node 8 and Tizen Node 4.4 (ES5 only). Credentials are installed at runtime,
   never included in the IPK. All network access is to the configured catalog. */
'use strict';
var Service = require('webos-service');
var fs = require('fs');
var path = require('path');
var https = require('https');
var url = require('url');
var crypto = require('crypto');
var service = new Service('dev.anidroid.tv.service');
var dataDir = process.env.ANI_DATA_DIR || '/media/internal'; // Tizen sets its app data dir
var configPath = dataDir + '/dev.anidroid.tv.connection.json';
var allowed = ['/v1/catalog', '/v1/status', '/v1/search', '/v1/details', '/v1/streams'];

function config(callback) {
    fs.readFile(configPath, 'utf8', function (err, text) {
        if (err) return callback(new Error('Catalog connection is not configured.'));
        try {
            var c = JSON.parse(text);
            var u = url.parse(c.url);
            if (u.protocol !== 'https:' || u.auth || (u.pathname && u.pathname !== '/') || !c.token || !/^[a-f0-9]{64}$/i.test(c.fingerprint) || !c.certificate) throw new Error('Invalid catalog connection.');
            callback(null, c);
        } catch (error) { callback(new Error('Invalid catalog connection.')); }
    });
}
service.register('configure', function (message) {
    var c=message.payload || {}, u=url.parse(String(c.url || ''));
    if(u.protocol!=='https:' || u.auth || (u.pathname && u.pathname!=='/') || typeof c.token!=='string' || c.token.length<16 || c.token.length>512 || !/^[a-f0-9]{64}$/i.test(c.fingerprint || '') || typeof c.certificate!=='string' || c.certificate.length>8192 || c.certificate.indexOf('BEGIN CERTIFICATE')<0) {
        return message.respond({returnValue:false,errorText:'Invalid catalog connection.'});
    }
    fs.writeFile(configPath,JSON.stringify({url:c.url,token:c.token,fingerprint:c.fingerprint,certificate:c.certificate}),{mode:384},function(err){
        message.respond({returnValue:!err,configured:!err,errorText:err?'Could not save the private connection.':''});
    });
});
service.register('status', function (message) {
    config(function (err) { message.respond({returnValue: !err, configured: !err, errorText: err ? err.message : ''}); });
});
service.register('request', function (message) {
    var payload = message.payload || {};
    if (allowed.indexOf(payload.path) < 0) return message.respond({returnValue: false, errorText: 'Unsupported request.'});
    config(function (err, c) {
        if (err) return message.respond({returnValue: false, errorText: err.message});
        var endpoint = url.parse(c.url);
        var params = payload.params || {};
        var query = Object.keys(params).map(function (key) { return encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key])); }).join('&');
        if (query.length > 1800) return message.respond({returnValue: false, errorText: 'Request is too long.'});
        var responded = false;
        function finish(value) { if (!responded) { responded = true; message.respond(value); } }
        var request = https.request({
            hostname: endpoint.hostname, port: endpoint.port || 443, path: payload.path + '?' + query, method: 'GET',
            ca: c.certificate, rejectUnauthorized: true,
            checkServerIdentity: function (_, cert) {
                var fingerprint = crypto.createHash('sha256').update(cert.raw).digest('hex');
                if (fingerprint !== c.fingerprint.toLowerCase()) return new Error('Catalog certificate does not match.');
                // Exact certificate pin establishes identity even when the LAN address changes.
            },
            headers: {'Authorization': 'Bearer ' + c.token, 'Accept': 'application/json'}
        }, function (response) {
            var bytes = 0, chunks = [];
            response.on('data', function (chunk) {
                bytes += chunk.length;
                if (bytes > 4 * 1024 * 1024) { request.destroy(); finish({returnValue: false, errorText: 'Catalog response is too large.'}); }
                else chunks.push(chunk);
            });
            response.on('end', function () {
                try {
                    var data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                    if (response.statusCode !== 200) return finish({returnValue: false, errorText: data.error || 'Catalog request failed.'});
                    if (data.streams) data.streams.forEach(function (stream) {
                        if (stream.relayPath && /^\/media\/[A-Za-z0-9_-]+\/[a-f0-9]+$/.test(stream.relayPath) && Number(stream.relayPort)>0 && Number(stream.relayPort)<65536) {
                            var host = endpoint.hostname.indexOf(':') >= 0 ? '[' + endpoint.hostname + ']' : endpoint.hostname;
                            var base = 'http://' + host + ':' + Number(stream.relayPort);
                            stream.tvUrl = base + stream.relayPath;
                            (stream.captions || []).forEach(function(caption){
                                if(/^\/media\/[A-Za-z0-9_-]+\/[a-f0-9]+$/.test(caption.relayPath || ''))caption.tvUrl=base+caption.relayPath;
                                delete caption.url;
                            });
                        }
                        // Raw signed provider URLs and headers are unnecessary in the TV UI.
                        delete stream.url; delete stream.headers;
                    });
                    finish({returnValue: true, data: data});
                } catch (error) { finish({returnValue: false, errorText: 'Unexpected catalog response.'}); }
            });
            response.on('error', function () { finish({returnValue: false, errorText: 'Catalog connection interrupted.'}); });
        });
        request.setTimeout(105000, function () { request.destroy(); finish({returnValue: false, errorText: 'Catalog request timed out. Retry shortly.'}); });
        request.on('error', function () { finish({returnValue: false, errorText: 'Cannot reach or verify the catalog server. Keep the server computer running on this network.'}); });
        request.end();
    });
});

// Device-local manual backup survives reinstall; no connection credentials are accepted.
var libraryBackupPath=dataDir+'/dev.anidroid.tv.library-backup.json';
service.register('saveLibraryBackup',function(message){
    var data=message.payload && message.payload.data;
    if(typeof data!=='string'||Buffer.byteLength(data)>2*1024*1024)return message.respond({returnValue:false,errorText:'Invalid backup.'});
    try{var parsed=JSON.parse(data);if(parsed.schema!==1||Object.keys(parsed).some(function(k){return ['schema','lists','preferences','shows','watched'].indexOf(k)<0;}))throw new Error();}
    catch(e){return message.respond({returnValue:false,errorText:'Invalid backup.'});}
    fs.writeFile(libraryBackupPath+'.tmp',data,{mode:384},function(error){if(error)return message.respond({returnValue:false,errorText:'Cannot save backup.'});fs.rename(libraryBackupPath+'.tmp',libraryBackupPath,function(error){message.respond(error?{returnValue:false,errorText:'Cannot save backup.'}:{returnValue:true});});});
});
service.register('loadLibraryBackup',function(message){
    fs.stat(libraryBackupPath,function(error,stat){if(error||stat.size>2*1024*1024)return message.respond({returnValue:false,errorText:'No valid local backup found.'});
        fs.readFile(libraryBackupPath,'utf8',function(error,data){message.respond(error?{returnValue:false,errorText:'Cannot read backup.'}:{returnValue:true,data:data});});
    });
});

// Automatic TV-only backup (adds resume positions and Continue Watching). It lives outside the app
// installation, keeps one previous copy, and is never overwritten with an empty library.
var autoBackupPath=dataDir+'/dev.anidroid.tv.auto-backup.json';
var autoKeys=['schema','lists','preferences','shows','watched','positions','recentPlays'];
function autoBackupHasData(p){
    var l=p.lists||{};
    return ['favorites','watchlist','history'].some(function(k){return Array.isArray(l[k])&&l[k].length>0;})||
        (Array.isArray(p.watched)&&p.watched.length>0)||(p.shows&&Object.keys(p.shows).length>0)||
        (p.positions&&Object.keys(p.positions).length>0)||(Array.isArray(p.recentPlays)&&p.recentPlays.length>0);
}
service.register('saveAutoBackup',function(message){
    var data=message.payload && message.payload.data;
    if(typeof data!=='string'||Buffer.byteLength(data)>2*1024*1024)return message.respond({returnValue:false,errorText:'Invalid backup.'});
    var parsed;
    try{parsed=JSON.parse(data);if(parsed.schema!==1||Object.keys(parsed).some(function(k){return autoKeys.indexOf(k)<0;}))throw new Error();}
    catch(e){return message.respond({returnValue:false,errorText:'Invalid backup.'});}
    if(!autoBackupHasData(parsed))return message.respond({returnValue:true,skipped:true});
    fs.rename(autoBackupPath,autoBackupPath+'.prev',function(){ // a missing first file is fine
        fs.writeFile(autoBackupPath+'.tmp',data,{mode:384},function(error){
            if(error)return message.respond({returnValue:false,errorText:'Cannot save backup.'});
            fs.rename(autoBackupPath+'.tmp',autoBackupPath,function(error){message.respond(error?{returnValue:false,errorText:'Cannot save backup.'}:{returnValue:true});});
        });
    });
});
service.register('loadAutoBackup',function(message){
    function read(file,next){
        fs.stat(file,function(error,stat){if(error||stat.size>2*1024*1024)return next();
            fs.readFile(file,'utf8',function(error,data){if(error)return next();
                try{var p=JSON.parse(data);if(p.schema!==1||!autoBackupHasData(p))return next();}catch(e){return next();}
                message.respond({returnValue:true,data:data});});});
    }
    read(autoBackupPath,function(){read(autoBackupPath+'.prev',function(){message.respond({returnValue:false,errorText:'No automatic backup found.'});});});
});
