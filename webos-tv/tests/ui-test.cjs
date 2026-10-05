// Run with NODE_PATH pointing to an installed playwright-core package.
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const os=require('node:os');
const http=require('node:http');const https=require('node:https');
const {chromium}=require('playwright-core');
const root=path.resolve(__dirname,'../app');
const fixture=path.resolve(__dirname,'../build/test-playback.mp4');fs.mkdirSync(path.dirname(fixture),{recursive:true});
const clips=path.resolve(__dirname,'../../anidroid/src/androidTest/assets/offline');
require('node:child_process').execFileSync('ffmpeg',['-v','error','-y','-i','concat:'+['clip-00.ts','clip-01.ts','clip-02.ts'].map(f=>path.join(clips,f)).join('|'),'-c','copy','-movflags','+faststart',fixture]);
const state=process.env.ANI_CATALOG_STATE || path.join(os.homedir(),'.local/share/ani-droid-catalog');
const connection=JSON.parse(fs.readFileSync(path.join(state,'connection.json'),'utf8'));
const ca=fs.readFileSync(path.join(state,'certificate.der'));
const cert='-----BEGIN CERTIFICATE-----\n'+ca.toString('base64').match(/.{1,64}/g).join('\n')+'\n-----END CERTIFICATE-----\n';
const server=http.createServer((req,res)=>{
 if(req.url==='/__fixture/en.vtt'){res.setHeader('Content-Type','text/vtt');res.end('WEBVTT\n\n00:00:01.000 --> 00:00:05.000\nFixture caption\n');return;}
 if(req.url==='/__fixture/video.mp4'){res.setHeader('Content-Type','video/mp4');res.setHeader('Accept-Ranges','bytes');const size=fs.statSync(fixture).size;const match=/bytes=(\d+)-(\d*)/.exec(req.headers.range||'');const start=match?Number(match[1]):0,end=match&&match[2]?Math.min(size-1,Number(match[2])):size-1;if(match){res.statusCode=206;res.setHeader('Content-Range',`bytes ${start}-${end}/${size}`);}res.setHeader('Content-Length',end-start+1);fs.createReadStream(fixture,{start,end}).pipe(res);return;}
 const file=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname.replace(/\/$/,'/index.html'));
 if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
 res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png'})[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res);
});
function fetchCatalog(route){return new Promise((resolve,reject)=>{
 const incoming=new URL(route.request().url());const request=https.get('https://localhost:'+new URL(connection.url).port+incoming.pathname.replace('/__catalog','')+incoming.search,{ca:cert,headers:{Authorization:'Bearer '+connection.token}},r=>{let chunks=[];r.on('data',c=>chunks.push(c));r.on('end',()=>resolve({status:r.statusCode,contentType:'application/json',body:Buffer.concat(chunks)}));});request.on('error',reject);
});}
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const profile=process.env.ANI_BROWSER_PROFILE || path.join(os.homedir(),'snap/brave/common/ani-tv-preview');
 const browser=await chromium.launchPersistentContext(profile,{executablePath:process.env.ANI_BROWSER||'/snap/bin/brave',headless:true,args:['--no-sandbox','--disable-gpu','--autoplay-policy=no-user-gesture-required'],viewport:{width:1920,height:1080}});
 try{
  await browser.route('**/__catalog/**',async route=>route.fulfill(await fetchCatalog(route)));
  await browser.addInitScript(()=>{try{localStorage.removeItem("favorites");localStorage.removeItem("uiTextSize");localStorage.removeItem("subtitleSize");localStorage.removeItem("subtitleColor");["recentPlays","watchlist","watched","showPreferences","subtitleDelay","autoNext"].forEach(k=>localStorage.removeItem(k));}catch(e){}window.ANI_TEST_API=(path,params)=>fetch('/__catalog'+path+'?'+new URLSearchParams(params)).then(r=>r.json()).then(d=>{if(d.error)throw new Error(d.error);return d;});});
  const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port);
  await page.locator('.card').first().waitFor();
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).fontSize),'36px');
  await page.getByRole('button',{name:'Larger text',exact:true}).click();
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).fontSize),'42px');
  await page.getByRole('button',{name:'Standard text',exact:true}).click();
  await page.locator('.card').first().focus();await page.keyboard.press('ArrowRight');
  assert.equal(await page.locator('.card').nth(1).evaluate(e=>e===document.activeElement),true);
  await page.locator('#source').selectOption('luffy');await page.waitForFunction(()=>document.querySelector('.provider')?.textContent==='LUFFY');
  await page.locator('#searchTab').click();await page.locator('#query').fill('The Goonies');await page.locator('#search').click();
  await page.locator('.card').filter({hasText:'The Goonies'}).first().click();await page.locator('.detail h2').waitFor();
  assert.match(await page.locator('.detail h2').textContent(),/Goonies/);
  await page.getByRole('button',{name:'Subtitle options',exact:true}).click();
  assert.equal(await page.locator('#subtitleSize').evaluate(e=>e===document.activeElement),true);
  await page.keyboard.press('Enter');
  assert.equal(await page.evaluate(()=>localStorage.getItem('subtitleSize')),'56');
  await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');
  assert.equal(await page.evaluate(()=>localStorage.getItem('subtitleColor')),'#ffeb3b');
  assert.match(await page.locator('#subtitleStyle').textContent(),/font-size:56px;color:#ffeb3b/);
  assert.equal(await page.locator('#subtitlePreview').evaluate(e=>getComputedStyle(e).fontSize),'56px');
  await page.keyboard.press('Escape');assert.equal(await page.locator('#subtitleSettings').isVisible(),false);
  assert.equal(await page.getByRole('button',{name:'Subtitle options',exact:true}).evaluate(e=>e===document.activeElement),true);

  await page.getByRole('button',{name:'♡ Favorite',exact:true}).click();await page.locator('#favoritesTab').click();
  assert.ok(await page.locator('.card').count()>=1);
  await page.locator('#catalogTab').click();await page.locator('#source').selectOption('ani');await page.locator('#query').fill('');await page.locator('#search').click();
  await page.waitForFunction(()=>document.querySelector('.provider')?.textContent==='ANI-CLI');
  await page.locator('#genre').selectOption('Action');await page.waitForTimeout(1000);
  const out=path.resolve(__dirname,'../build/tv-catalog-preview.png');fs.mkdirSync(path.dirname(out),{recursive:true});await page.screenshot({path:out});

  await page.evaluate(()=>{
   window.fixtureTitle={provider:'luffy',id:'tv-123',name:'Fixture series',info:'Test series',series:true};window.fixtureCalls=[];
   window.ANI_TEST_API=async(path,params)=>{
    if(path==='/v1/search')return {items:[window.fixtureTitle],hasMore:false};
    if(path==='/v1/catalog')return {items:[window.fixtureTitle],hasMore:false,total:1,genres:[],coverage:{tagged:0,total:1}};
    if(path==='/v1/details')return {title:window.fixtureTitle,episodes:[{id:'one',season:1,number:'1'},{id:'two',season:1,number:'2'}],audio:[{id:'sub',label:'Original'}]};
    if(path==='/v1/streams'){window.fixtureCalls.push(params);return {streams:[{label:'Fixture provider',tvUrl:'/__fixture/video.mp4',height:720,heights:[360,720,1080],sources:['Fixture available'],captions:[{name:'English',tvUrl:'/__fixture/en.vtt'}]}]};}
   };
  });
  await page.locator('#searchTab').click();await page.locator('#query').fill('Fixture');await page.locator('#search').click();await page.locator('.card').filter({hasText:'Fixture series'}).click();
  await page.getByRole('button',{name:'+ Watchlist',exact:true}).click();assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('watchlist')).length),1);
  await page.getByRole('button',{name:'Episode 1',exact:true}).click();await page.waitForFunction(()=>document.getElementById('video').readyState>=2);
  await page.evaluate(()=>{document.getElementById('video').pause();document.getElementById('video').currentTime=2;});
  await page.keyboard.press('ArrowDown');assert.equal(await page.locator('#subtitleSettings').isVisible(),true);
  await page.locator('#subtitleLater').click();await page.waitForFunction(()=>document.getElementById('video').textTracks[0]?.cues?.length>0);
  assert.equal(await page.evaluate(()=>document.getElementById('video').textTracks[0].cues[0].startTime),1.5);
  await page.locator('#subtitleTrack').click();assert.match(await page.locator('#subtitleTrack').textContent(),/Off/);
  await page.locator('#subtitleTrack').click();assert.match(await page.locator('#subtitleTrack').textContent(),/English/);
  await page.locator('#streamSource').click();await page.waitForFunction(()=>window.fixtureCalls.at(-1).source==='cinejoy');await page.waitForFunction(()=>document.getElementById('video').readyState>=2);await page.waitForFunction(()=>Math.abs(document.getElementById('video').currentTime-2)<.2);
  await page.locator('#videoQuality').click();await page.waitForFunction(()=>window.fixtureCalls.at(-1).height===360);await page.waitForFunction(()=>document.getElementById('video').readyState>=2);
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('showPreferences'))['luffy:tv-123'].source),'cinejoy');
  await page.locator('#watchedToggle').click();assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('watched')).includes('luffy:tv-123:1:1')),true);
  await page.locator('#watchedToggle').click();
  await page.locator('#subtitleDone').click();await page.evaluate(()=>{const v=document.getElementById('video');v.pause();v.currentTime=2;});await page.waitForFunction(()=>Math.abs(document.getElementById('video').currentTime-2)<.2);await page.waitForTimeout(5200);await page.keyboard.press('ArrowUp');await page.locator('#stop').click();
  await page.locator('#continueTab').click();await page.getByRole('button',{name:'Fixture series · Episode 1',exact:true}).click();await page.waitForFunction(()=>document.getElementById('video').currentTime>=2);
  await page.evaluate(()=>{const v=document.getElementById('video');v.currentTime=v.duration-.2;v.play();});await page.locator('#nextUp').waitFor();
  await page.keyboard.press('Enter');assert.equal(await page.locator('#nextUp').isVisible(),false);assert.equal(await page.evaluate(()=>window.fixtureCalls.at(-1).episode),'1');
  await page.locator('#nextEpisode').click();await page.waitForFunction(()=>window.fixtureCalls.at(-1).episode==='2');
  await page.evaluate(()=>document.getElementById('video').dispatchEvent(new Event('error')));await page.getByRole('button',{name:'Retry fresh stream',exact:true}).click();await page.waitForFunction(()=>window.fixtureCalls.length>=4);
  await page.keyboard.press('ArrowUp');await page.locator('#stop').click();await page.locator('#watchlistTab').click();assert.equal(await page.locator('.card').count(),1);
  await page.locator('#catalogTab').click();await page.locator('#sort').selectOption('recent');
  await page.evaluate(()=>{window.ANI_TEST_BACKUP=async(method,params)=>{if(method==='saveLibraryBackup'){window.fixtureBackup=params.data;return {returnValue:true};}return {returnValue:true,data:window.fixtureBackup};};localStorage.setItem('token','private-fixture-value');});
  await page.locator('#backupOptions').click();await page.locator('#saveBackup').click();await page.waitForFunction(()=>!!window.fixtureBackup);
  assert.equal(await page.evaluate(()=>window.fixtureBackup.includes('private-fixture-value')),false);
  await page.evaluate(()=>localStorage.setItem('watchlist','[]'));await page.locator('#restoreBackup').click();await page.waitForFunction(()=>JSON.parse(localStorage.getItem('watchlist')).length===1);
  await page.locator('#backupData').fill('{"schema":1,"token":"bad"}');await page.locator('#importBackupText').click();assert.match(await page.locator('#backupStatus').textContent(),/Unsupported/);
  await page.keyboard.press('Escape');assert.equal(await page.locator('#backupSettings').isVisible(),false);
  console.log('TV UI passed: catalog, D-pad controls, search, watchlist, local resume, subtitle timing/tracks, next-episode cancellation, retry and recent sorting.');
 } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e.message);server.close();process.exitCode=1;});
