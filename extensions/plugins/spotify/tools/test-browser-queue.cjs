// Run with JSON produced by PlaybackScripts against the current SpotifyPlayback class.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const scripts=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const source=fs.readFileSync('extensions/plugins/spotify/app/src/main/java/com/music/streamx/extensions/spotify/SpotifyRemotePlayer.java','utf8');
const hook=source.split('private static final String HOOK = """')[1].split('""";')[0].replaceAll('\\\\','\\');
const wanted='1234567890123456789012',other='abcdefghijklmnopqrstuv';
let actual=wanted,clicks=0,title='New song',mobileTitle=null;
class Media {
 constructor(tag='AUDIO'){this.tagName=tag;this.readyState=4;this.paused=true;this.plays=0;this.listeners={};}
 pause(){this.paused=true;}
 play(){this.paused=false;this.plays++;return Promise.resolve();}
 addEventListener(event,handler){this.listeners[event]=handler;}
}
Object.defineProperty(Media.prototype,'muted',{configurable:true,get(){return this._muted||false;},set(value){this._muted=!!value;}});
const media=new Media();
const button={disabled:false,getAttribute:()=> 'Play',querySelector:()=>null,click(){clicks++;}};
const sandbox={window:{dispatchEvent(){}},location:{origin:'https://open.spotify.com',pathname:'/track/'+wanted},HTMLMediaElement:Media,
 document:{querySelector(selector){if(selector.includes('floating-now-playing-bar'))return mobileTitle?{textContent:mobileTitle}:null;if(selector.includes('context-item-link')) return {getAttribute:()=>'/track/'+actual};if(selector==='main h1')return null;return button;},querySelectorAll:selector=>selector==='h1'?[{textContent:title}]:[]},
 MutationObserver:class{observe(){}},queueMicrotask:callback=>callback(),Promise,Set,Date,PopStateEvent:class{},
 history:{state:{},pushState(state,unused,path){sandbox.location.pathname=path;}}};
vm.createContext(sandbox);vm.runInContext(hook,sandbox);
sandbox.window.__streamxWantedTrack=wanted;
const canvas=new Media('VIDEO');canvas.play();
assert.equal(sandbox.window.__streamxMedia,undefined,'Canvas starting before audio must not publish a song clock');
canvas.listeners.ended();
assert.notEqual(sandbox.window.__streamxEnded,true,'Canvas completion must not end the host queue entry');
media.play();
canvas.play();
assert.equal(sandbox.window.__streamxMedia,media,'Canvas must not replace the song audio');
assert.equal(media.plays,1);assert.equal(sandbox.window.__streamxMatchedTrack,true);
media.listeners.ended();assert.equal(sandbox.window.__streamxEnded,true);
media.play();assert.equal(media.plays,1,'Spotify auto-next must not clear the completion latch');
assert.equal(vm.runInContext(scripts.renderer,sandbox),'ended','polling must not restart the finished song');
// A newly requested queue entry can play normally.
vm.runInContext(scripts.navigate,sandbox);title='New song';
sandbox.window.__streamxPreviousTitle='Previous';
vm.runInContext(scripts.renderer,sandbox);media.play();assert.equal(media.plays,2);
sandbox.window.__streamxCheckTrack(); // The status poll observes local playback after play().
// Detect a change in Now Playing even when an HTML ended event is missed.
actual=other;media.play();assert(media.paused);assert.equal(media.plays,2);
assert.equal(sandbox.window.__streamxEnded,true,'unexpected Spotify track change must yield host queue completion');
// Initial stale audio must be paused and selected via the track page, never accepted as playing.
vm.runInContext(scripts.navigate,sandbox);sandbox.window.__streamxPreviousTitle='Previous';
sandbox.window.__streamxMedia=media;media.paused=false;
assert.equal(vm.runInContext(scripts.renderer,sandbox),'clicked');assert(media.muted,'stale audio must remain inaudible during selection');
assert(clicks>0);
console.log('PASS: completion latch, blocked Spotify auto-next, missed-ended identity detection, explicit new load, stale-audio selection');

// A successful click gets time to create media; polling must not hammer Play.
const clicksAfterSelection=clicks;
assert.equal(vm.runInContext(scripts.renderer,sandbox),'waiting for audio');
assert.equal(clicks,clicksAfterSelection);
// A Pause control for another track/device must never be toggled as a start attempt.
sandbox.window.__streamxPlayAttempt=0;
button.getAttribute=()=> 'Pause';
assert.equal(vm.runInContext(scripts.renderer,sandbox),'waiting for audio');
assert.equal(clicks,clicksAfterSelection);
console.log('PASS: startup cooldown and no remote Pause toggling');

function promptFixture(script,deviceDialog=true,origin='https://open.spotify.com') {
 const count={remote:0,local:0};
 const remote={textContent:'Continue',disabled:false,getClientRects:()=>[{}],click(){count.remote++;}};
 const local={textContent:'Listen on this phone',disabled:false,getClientRects:()=>[{}],click(){count.local++;}};
 const body={};const container={textContent:deviceDialog?'You are listening on Web Player (Chrome) Continue Listen on this phone':'Sign in Continue',parentElement:body,querySelectorAll:()=>deviceDialog?[remote,local]:[remote]};
 remote.parentElement=container;local.parentElement=container;
 const context={window:{},location:{origin},document:{body,querySelectorAll:()=>deviceDialog?[remote,local]:[remote]},WeakMap,Date,
 MutationObserver:class{observe(){}},queueMicrotask:fn=>fn()};
 vm.createContext(context);vm.runInContext(script,context);return count;
}
assert.deepEqual(promptFixture(scripts.localPrompt),{remote:0,local:1});
assert.deepEqual(promptFixture(scripts.continuePrompt),{remote:1,local:0});
assert.deepEqual(promptFixture(scripts.localPrompt,false),{remote:0,local:0});
assert.deepEqual(promptFixture(scripts.localPrompt,true,'https://accounts.spotify.com'),{remote:0,local:0});
console.log('PASS: renderer selects local device; account browser preserves remote; login and other origins untouched');

// Spotify's headings need not be descendants of a literal <main> element.
vm.runInContext(scripts.navigate,sandbox);
sandbox.window.__streamxPreviousTitle='Previous';title='Previous';
assert.equal(vm.runInContext(scripts.renderer,sandbox),'waiting for track page');
title='New song';sandbox.window.__streamxMedia=null;
vm.runInContext(scripts.renderer,sandbox);
assert.equal(sandbox.window.__streamxRoutePending,false,'a matching h1 outside main must release warm navigation');
console.log('PASS: waits for the target heading, accepts Spotify heading outside main');

// A cold page may ignore its first Play click while initializing the player.
actual=wanted;button.getAttribute=()=> 'Play';
sandbox.window.__streamxEnded=false;sandbox.window.__streamxMedia=null;
sandbox.window.__streamxRoutePending=false;
const clock=Date.now();sandbox.Date={now:()=>clock};
sandbox.window.__streamxPlayAttempt=clock-1000;
let prior=clicks;
assert.equal(vm.runInContext(scripts.renderer,sandbox),'waiting for audio');
assert.equal(clicks,prior,'do not retry every status poll');
sandbox.Date={now:()=>clock+1000};
assert.equal(vm.runInContext(scripts.renderer,sandbox),'clicked');
assert.equal(clicks,prior+1,'retry an unaccepted startup click before the old eight-second delay');
sandbox.window.__streamxMedia=media;media.paused=true;
sandbox.Date={now:()=>clock+3000};
prior=clicks;
assert.equal(vm.runInContext(scripts.renderer,sandbox),'waiting for audio');
assert.equal(clicks,prior,'existing media keeps its longer buffering grace period');
sandbox.window.__streamxMedia=null;button.getAttribute=()=> 'Pause';
sandbox.Date={now:()=>clock+10000};
vm.runInContext(scripts.renderer,sandbox);
assert.equal(clicks,prior,'a startup retry must never toggle Pause');
sandbox.Date=Date;
console.log('PASS: bounded cold-start retry, buffering grace, no Pause toggling');

// Mobile Spotify has no desktop Now Playing link; use its actual floating bar title.
actual=null;mobileTitle='Previous song';
sandbox.window.__streamxWantedTitle='New song';sandbox.window.__streamxWantedTrack=wanted;
sandbox.window.__streamxMatchedTrack=false;sandbox.window.__streamxEnded=false;
sandbox.window.__streamxPaused=false;sandbox.window.__streamxAwaitingSelection=false;
media.muted=false;media.__streamxIdentityMuted=false;media.play();
assert.equal(sandbox.window.__streamxCheckTrack(),'title mismatch');
assert.equal(media.muted,true,'a different mobile song must never become audible');
media.muted=false;assert.equal(media.muted,true,'Spotify cannot undo the identity mute before verification');
assert.notEqual(sandbox.window.__streamxEnded,true,'stale startup metadata must not skip the host queue');
mobileTitle='  NEW song  ';
assert.equal(sandbox.window.__streamxCheckTrack(),wanted);
assert.equal(media.muted,false,'only matching mobile identity may unmute');
mobileTitle='Spotify auto-next';sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxEnded,true);
assert.equal(media.muted,true);assert.equal(media.paused,true);
console.log('PASS: mobile identity gates audibility, stale startup does not skip, Spotify auto-next is stopped');

// Exact playing IDs outrank identical display titles; explicitly linked market versions are valid.
sandbox.window.__streamxEnded=false;sandbox.window.__streamxMatchedTrack=false;
mobileTitle='New song';actual=null;
let current={uri:'spotify:track:'+other,name:'New song'};
sandbox.window.__streamxPlaybackStore={getState:()=>({playback:{current:{track_window:{current_track:current}}}})};
assert.equal(sandbox.window.__streamxObservedTrack(),other);
assert.equal(sandbox.window.__streamxCheckTrack(),other);
assert.equal(media.muted,true,'matching titles must not accept a different playing ID');
current={...current,linked_from:{uri:'spotify:track:'+wanted}};
assert.equal(sandbox.window.__streamxObservedTrack(),wanted);
sandbox.window.__streamxCheckTrack();
assert.equal(media.muted,false,'Spotify-linked market versions can play');
console.log('PASS: exact item IDs override titles, linked market versions accepted');

// Identity protection must not suppress Spotify advertisements or advance the queue for them.
current={uri:'spotify:ad:test',type:'ad',name:'Advertisement'};
sandbox.window.__streamxEnded=false;sandbox.window.__streamxMatchedTrack=true;
media.play();
assert.equal(media.muted,false,'Spotify ads remain audible');
media.listeners.ended();
assert.equal(sandbox.window.__streamxEnded,false,'ad completion is not song completion');
console.log('PASS: advertisements remain audible and do not advance the host queue');

// A desktop context link can name an album while the widget identifies the playing track.
sandbox.window.__streamxPlaybackStore=null;actual=null;
const originalQS=sandbox.document.querySelector,originalQSA=sandbox.document.querySelectorAll;
const widgetLink={getAttribute:()=>'/track/'+wanted,textContent:'New song'};
sandbox.document.querySelectorAll=selector=>selector.includes('now-playing-widget')?[widgetLink]:originalQSA(selector);
sandbox.document.querySelector=selector=>selector.includes('context-item-link')?{getAttribute:()=>'/album/'+other,textContent:'Old album'}:originalQS(selector);
assert.equal(sandbox.window.__streamxObservedTrack(),wanted);
assert.equal(sandbox.window.__streamxObservedTitle(),'New song');
sandbox.document.querySelector=originalQS;sandbox.document.querySelectorAll=originalQSA;
console.log('PASS: desktop playing-item link wins over an unrelated album context');

// A generic top-bar Play resumes the previous song, so only track/page controls may be selected.
actual=wanted;mobileTitle=null;button.getAttribute=()=> 'Play';
sandbox.window.__streamxEnded=false;sandbox.window.__streamxPlayAttempt=0;
sandbox.window.__streamxPlayRequested=null;sandbox.window.__streamxMedia=null;
sandbox.window.__streamxRoutePending=false;
let topbarClicks=0;
sandbox.document.querySelector=selector=>selector==='main button[data-testid="play-button"]'?{...button,click(){topbarClicks++;}}:originalQS(selector);
assert.equal(vm.runInContext(scripts.renderer,sandbox),'clicked');
assert.equal(topbarClicks,0,'never select the generic resume control');
sandbox.document.querySelector=originalQS;
console.log('PASS: track selection never clicks the generic top-bar resume button');

// A remote device's matching title must not arm local auto-next detection.
sandbox.window.__streamxMedia=null;sandbox.window.__streamxMatchedTrack=false;
sandbox.window.__streamxEnded=false;sandbox.window.__streamxPaused=false;
sandbox.window.__streamxAwaitingSelection=true;sandbox.window.__streamxPlaybackStore=null;
actual=wanted;
sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxMatchedTrack,false,'remote metadata alone is not local playback');
sandbox.window.__streamxAwaitingSelection=false;
canvas.play();
assert.equal(sandbox.window.__streamxMatchedTrack,false,'Canvas cannot arm song completion');
actual=other;media.readyState=1;media.play();
assert.equal(sandbox.window.__streamxEnded,false,'stale matching metadata must not terminate the next local startup');
assert.equal(media.paused,false,'local startup can proceed while identity is being checked');
assert.equal(media.muted,true,'unverified audio remains muted');
actual=wanted;sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxMatchedTrack,false,'buffering media does not arm completion');
media.readyState=4;media.paused=true;sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxMatchedTrack,false,'paused media does not arm completion');
media.play();sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxMatchedTrack,true,'ready local playback arms completion');
actual=other;sandbox.window.__streamxCheckTrack();
assert.equal(sandbox.window.__streamxEnded,true,'a real local track change still yields to the host queue');
console.log('PASS: remote metadata, Canvas, buffering and pauses cannot falsely arm local completion');

// Local decoder initialization must proceed even before selection is acknowledged.
sandbox.window.__streamxEnded=false;sandbox.window.__streamxPaused=false;
sandbox.window.__streamxMatchedTrack=false;sandbox.window.__streamxAwaitingSelection=true;
actual=other;
const priming=new Media();priming.readyState=1;
priming.play();
assert.equal(priming.plays,1,'selection pending must not suppress the decoder play call');
assert.equal(priming.muted,true,'decoder priming cannot expose an unverified song');
actual=wanted;priming.readyState=4;sandbox.window.__streamxCheckTrack();
assert.equal(priming.muted,true,'matching stale metadata stays muted while selection is pending');
sandbox.window.__streamxAwaitingSelection=false;sandbox.window.__streamxCheckTrack();
assert.equal(priming.muted,false,'selected matching local audio becomes audible');
assert.equal(sandbox.window.__streamxMatchedTrack,true);
console.log('PASS: decoder priming proceeds silently until selection and identity are verified');

// Title links navigate; only the requested row's left control selects its recording.
function rowSelectionFixture(href, mode='play') {
 const count={play:0,title:0,double:0,other:0};
 const link={getAttribute:()=>href,click(){count.title++;}};
 const control={disabled:mode==='disabled',getAttribute:key=>key==='aria-label'?(mode==='pause'?'Pause New song':'Play New song'):null,
  querySelector:()=>null,click(){count.play++;}};
 const thumbnail={dispatchEvent(e){assert.equal(e.type,'dblclick');assert.equal(e.detail,2);count.double++;}};
 const row={querySelectorAll:selector=>selector==='a[href]'?[link]:mode==='double'?[]:[control],querySelector:()=>thumbnail};
 const otherRow={querySelectorAll:selector=>selector==='a[href]'?[{getAttribute:()=>'/track/'+other}]:[{...control,click(){count.other++;}}]};
 const context={window:{},location:{origin:'https://open.spotify.com'},document:{querySelectorAll:()=>[otherRow,row]},URL,
  MouseEvent:class{constructor(type,options){this.type=type;Object.assign(this,options);}}};
 vm.createContext(context);
 return {result:vm.runInContext(scripts.selectRow,context),count,window:context.window};
}
for(const href of ['/track/'+wanted,'/intl-en/track/'+wanted+'?si=test','https://open.spotify.com/track/'+wanted+'/?si=test']) {
 const result=rowSelectionFixture(href);
 assert.equal(result.result,'clicked');assert.deepEqual(result.count,{play:1,title:0,double:0,other:0});
 assert.equal(result.window.__streamxSelectionControl,'row-play');
}
const double=rowSelectionFixture('/track/'+wanted,'double');
assert.equal(double.result,'clicked');assert.deepEqual(double.count,{play:0,title:0,double:1,other:0});
assert.equal(double.window.__streamxSelectionControl,'row-double-click');
for(const mode of ['pause','disabled']) {
 const result=rowSelectionFixture('/track/'+wanted,mode);
 assert.notEqual(result.result,'clicked');assert.deepEqual(result.count,{play:0,title:0,double:0,other:0});
}
assert.equal(rowSelectionFixture('https://evil.test/track/'+wanted).result,'missing row');
assert.equal(rowSelectionFixture('/album/'+wanted).result,'missing row');
console.log('PASS: exact row Play, localized/absolute links, thumbnail double-click, no title/other-row/Pause/disabled clicks');

// Desktop controls can target Firefox on another device. Activate only this renderer.
function deviceFixture(remote=true,open=false,origin='https://open.spotify.com') {
 const count={picker:0,local:0,other:0};
 const local={getAttribute:()=>null,click(){count.local++;}};
 const row={querySelector:selector=>selector.includes('list-row-title')?{textContent:'This web browser'}:local};
 const otherRow={querySelector:()=>({textContent:'Web Player (Firefox)',click(){count.other++;}})};
 const picker={getAttribute:()=>open?'true':'false',click(){count.picker++;}};
 const context={window:{},location:{origin},Date,document:{querySelector:()=>picker,
  querySelectorAll:selector=>selector==='button'?(remote?[{textContent:'Playing on Web Player (Firefox)'}]:[]):open?[otherRow,row]:[]}};
 vm.createContext(context);const result=vm.runInContext(scripts.activateLocal,context);
 return {context,count,result};
}
assert.deepEqual(deviceFixture(true,false).count,{picker:1,local:0,other:0});
const device=deviceFixture(true,true);
assert.equal(device.result,'waiting for local device');assert.deepEqual(device.count,{picker:0,local:1,other:0});
vm.runInContext(scripts.activateLocal,device.context);assert.equal(device.count.local,1,'activation waits before retrying');
assert.deepEqual(deviceFixture(false,true).count,{picker:0,local:0,other:0});
assert.deepEqual(deviceFixture(true,true,'https://accounts.spotify.com').count,{picker:0,local:0,other:0});
console.log('PASS: remote-device controls activate only This web browser, with cooldown and exact origin');

actual=other;media.paused=false;
sandbox.window.__streamxMatchedTrack=false;sandbox.window.__streamxEnded=false;
sandbox.window.__streamxAwaitingSelection=false;sandbox.window.__streamxFreeShuffle=true;
sandbox.window.__streamxCheckTrack();
assert.equal(media.muted,true,'a free-shuffle flag must not make the wrong recording audible');
console.log('PASS: shuffle cannot bypass requested-track identity');

let genericClicks=0;
const generic={textContent:'',getAttribute:()=> 'Play',click(){genericClicks++;}};
const noFallback={window:{__streamxWantedTrack:wanted,__streamxAwaitingSelection:true,__streamxCheckTrack:()=>other},
 location:{origin:'https://open.spotify.com',pathname:'/track/'+wanted},Date,URL,
 document:{querySelector:selector=>selector==='button[data-testid="play-button"]'?generic:null,
 querySelectorAll:selector=>selector==='button'?[generic]:[]}};
vm.createContext(noFallback);
assert.equal(vm.runInContext(scripts.renderer,noFallback),'waiting');
vm.runInContext(scripts.resume,noFallback);
assert.equal(genericClicks,0,'neither selection nor resume may play a previous generic context');
console.log('PASS: missing exact controls never fall back to a generic Play or resume stale audio');
