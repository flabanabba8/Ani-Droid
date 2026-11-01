// Run with NODE_PATH pointing to an installed playwright-core package.
// Checks the automatic backup: save payload, never-empty rule in the service, restore into empty storage.
const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const http=require('node:http');const Module=require('node:module');
const {chromium}=require('playwright-core');
const root=path.resolve(__dirname,'../app');

// --- service rules, with webos-service and the TV filesystem stubbed ---
function loadService(files){
 const handlers={};const realLoad=Module._load;const realFs=require('node:fs');
 const fakeFs=Object.assign({},realFs,{
  rename:(a,b,cb)=>{if(!(a in files))return cb(new Error('ENOENT'));files[b]=files[a];delete files[a];cb(null);},
  writeFile:(p,d,o,cb)=>{files[p]=d;cb(null);},
  stat:(p,cb)=>p in files?cb(null,{size:Buffer.byteLength(files[p])}):cb(new Error('ENOENT')),
  readFile:(p,e,cb)=>p in files?cb(null,files[p]):cb(new Error('ENOENT'))});
 Module._load=function(r,...rest){if(r==='webos-service')return function(){this.register=(n,f)=>{handlers[n]=f;};};if(r==='fs')return fakeFs;return realLoad.call(this,r,...rest);};
 delete require.cache[require.resolve('../service/service.js')];require('../service/service.js');Module._load=realLoad;
 return (name,payload)=>new Promise(res=>handlers[name]({payload,respond:res}));
}
const auto='/media/internal/dev.anidroid.tv.auto-backup.json';
const empty=JSON.stringify({schema:1,lists:{favorites:[],watchlist:[],history:[]},shows:{},watched:[],positions:{},recentPlays:[]});
const full=JSON.stringify({schema:1,lists:{favorites:[],watchlist:[],history:[]},shows:{},watched:[],positions:{'position:luffy:movie-1:1:1':120},recentPlays:[]});
(async()=>{
 const files={};const svc=loadService(files);
 assert.equal((await svc('saveAutoBackup',{data:empty})).skipped,true);assert.ok(!(auto in files),'empty library must not be written');
 assert.equal((await svc('saveAutoBackup',{data:full})).returnValue,true);assert.equal(files[auto],full);
 assert.equal((await svc('saveAutoBackup',{data:full.replace('120','240')})).returnValue,true);assert.equal(files[auto+'.prev'],full,'previous copy kept');
 assert.equal((await svc('saveAutoBackup',{data:empty})).skipped,true);assert.ok(files[auto].includes('240'),'good backup survives an empty save');
 assert.equal((await svc('saveAutoBackup',{data:'{"schema":2}'})).returnValue,false);
 delete files[auto];assert.equal((await svc('loadAutoBackup',{})).data,full,'falls back to the previous copy');
 console.log('service ok');

 // --- app: save payload, then restore into empty storage ---
 const server=http.createServer((req,res)=>{const f=path.resolve(root,'.'+(req.url==='/'?'/index.html':new URL(req.url,'http://x').pathname));if(!f.startsWith(root)||!fs.existsSync(f)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(f)]||'application/octet-stream');res.end(fs.readFileSync(f));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.ANI_BROWSER||'/snap/bin/brave',headless:true,args:['--no-sandbox']});
 try{
  const ctx=await browser.newContext();const page=await ctx.newPage();let saved=null;
  await page.exposeFunction('__svc',(m,p)=>{if(m==='saveAutoBackup'){saved=p.data;return {returnValue:true};}if(m==='loadAutoBackup'){if(!saved)throw new Error('none');return {returnValue:true,data:saved};}throw new Error('unexpected '+m);});
  await page.addInitScript(()=>{window.ANI_TEST_BACKUP=(m,p)=>window.__svc(m,p);});
  const url='http://127.0.0.1:'+server.address().port+'/';
  await page.goto(url);
  await page.evaluate(()=>{localStorage.clear();
   localStorage.setItem('favorites',JSON.stringify([{provider:'luffy',id:'movie-1',name:'Test Film',info:'2020 · Movie',series:false,genres:[],poster:''}]));
   localStorage.setItem('position:luffy:movie-1:1:1','1234');
   localStorage.setItem('recentPlays',JSON.stringify([{title:{provider:'luffy',id:'movie-1',name:'Test Film',info:'2020 · Movie',series:false,genres:[]},episode:{season:1,number:1,name:'Movie'},audio:'sub'}]));
   Object.defineProperty(document,'hidden',{value:true,configurable:true});document.dispatchEvent(new Event('visibilitychange'));});
  await page.waitForFunction(()=>true);await new Promise(r=>setTimeout(r,500));
  assert.ok(saved,'autosave payload sent');const snap=JSON.parse(saved);
  assert.equal(snap.positions['position:luffy:movie-1:1:1'],1234);assert.equal(snap.recentPlays.length,1);assert.equal(snap.lists.favorites.length,1);
  await page.evaluate(()=>localStorage.clear());await page.reload();await new Promise(r=>setTimeout(r,800));
  const out=await page.evaluate(()=>({pos:localStorage.getItem('position:luffy:movie-1:1:1'),fav:localStorage.getItem('favorites'),plays:localStorage.getItem('recentPlays')}));
  assert.equal(out.pos,'1234');assert.ok(out.fav.includes('Test Film'));assert.ok(out.plays.includes('"number":1'));
  console.log('app ok');
 } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exit(1);});
