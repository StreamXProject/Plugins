const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('extensions/plugins/spotify/app/src/main/java/com/music/streamx/extensions/spotify/SpotifySession.java','utf8');
const hook=source.split('private static final String HOOK = """')[1].split('""";')[0].replaceAll('\\\\','\\');
let records=[];
function Media(){}Media.prototype.play=function(){};
class XHR {open(){}send(){}addEventListener(){}}
const sandbox={window:{fetch:()=>Promise.resolve({clone:()=>({text:async()=>JSON.stringify({granted_token:{token:'fixture-client'}})})})},
 document:{querySelectorAll:()=>records.map(v=>({textContent:typeof v==='string'?v:JSON.stringify(v)}))},HTMLMediaElement:Media,XMLHttpRequest:XHR,DOMException,Promise};
vm.createContext(sandbox);vm.runInContext(hook,sandbox);
records=[{accessToken:'anonymous',isAnonymous:true},'invalid json',{unrelated:'metadata'}];
sandbox.window.__streamxRestoreSession();assert.equal(sandbox.window.__streamxSession.bearer,undefined);
records.push({accessToken:'fixture-bearer',isAnonymous:false,accessTokenExpirationTimestampMs:123456});
sandbox.window.__streamxRestoreSession();assert.equal(sandbox.window.__streamxSession.bearer,'fixture-bearer');assert.equal(sandbox.window.__streamxSession.expires_at,123456);
(async()=>{await sandbox.window.fetch('https://clienttoken.spotify.com/v1/clienttoken');await new Promise(r=>setTimeout(r,0));assert.equal(sandbox.window.__streamxSession.client,'fixture-client');console.log('PASS: embedded signed-in session restores without /api/token; anonymous/malformed metadata ignored; client-token fetch still captured');})();
