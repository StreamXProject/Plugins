// node extensions/plugins/spotify/tools/test-browser-touch.cjs <PlaybackScripts JSON>
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const script=JSON.parse(fs.readFileSync(process.argv[2],'utf8')).touch;
function fixture(origin='https://open.spotify.com',desktop=true) {
 let time=1000;const listeners={},events=[],styles=[];
 const row={key:'track-a',isConnected:true,querySelector:()=>({getAttribute:()=>row.key})};
 const other={...row,key:'track-b',querySelector:()=>({getAttribute:()=>other.key})};
 const target=(owner=row,interactive=false)=>({closest:s=>s.includes('contenteditable')?(interactive?{}:null):owner,
  dispatchEvent(e){events.push(e);emit('dblclick',this,{...e});}});
 const image=target(),background=target(),button=target(row,true),link=target(row,true),second=target(other);
 function emit(type,target,props={}) {
  const event={target,cancelable:true,prevented:false,stopped:false,preventDefault(){this.prevented=true;},stopImmediatePropagation(){this.stopped=true;},...props};
  for(const fn of listeners[type]||[]) {fn(event);if(event.stopped)break;}return event;
 }
 const context={window:{},location:{origin},navigator:{userAgent:desktop?'X11; Linux x86_64':'Android'},Math,Date:{now:()=>time},
  MouseEvent:class{constructor(type,options){this.type=type;Object.assign(this,options);}},document:{
   head:{appendChild:s=>styles.push(s)},getElementById:id=>styles.find(s=>s.id===id),createElement:()=>({}),
   addEventListener(type,fn){(listeners[type]||=[]).push(fn);}}};
 vm.createContext(context);vm.runInContext(script,context);
 const touch=(x=20,y=30)=>({clientX:x,clientY:y});
 function tap(target=image,{x=20,y=30,duration=50}={}) {
  emit('touchstart',target,{touches:[touch(x,y)]});time+=duration;
  return emit('touchend',target,{touches:[],changedTouches:[touch(x,y)]});
 }
 return {context,events,styles,row,image,background,button,link,second,tap,emit,touch,advance:n=>time+=n};
}
let f=fixture();assert.equal(f.tap().prevented,false);assert.equal(f.events.length,0,'single tap keeps normal behavior');
f.advance(100);assert.equal(f.tap().prevented,true);assert.equal(f.events.length,1);assert.equal(f.events[0].type,'dblclick');assert.equal(f.events[0].detail,2);
assert.equal(f.styles[0].textContent.includes('touch-action: manipulation'),true,'preserve pan/pinch while disabling row double-tap zoom');
assert.equal(f.emit('dblclick',f.image).stopped,true,'native touch compatibility dblclick cannot play twice');
assert.equal(f.emit('dblclick',f.image,{sourceCapabilities:{firesTouchEvents:false}}).stopped,false,'physical mouse double-clicks remain native');
vm.runInContext(script,f.context);f.advance(700);f.tap();f.advance(50);f.tap();assert.equal(f.events.length,2,'reinstalling after page load does not duplicate listeners');
for(const kind of ['button','link']) {
 f=fixture();f.tap(f[kind]);f.tap(f[kind]);assert.equal(f.events.length,0,'interactive controls are untouched');
}
f=fixture();f.tap(f.background);f.tap(f.background);assert.equal(f.events.length,1,'row backgrounds also support double-tap');
f=fixture();f.tap();f.tap(f.second);assert.equal(f.events.length,0,'different rows cannot form a double-tap');
f=fixture();f.tap();f.row.key='recycled';f.tap();assert.equal(f.events.length,0,'virtualized rows cannot select the previous recording');
f=fixture();f.tap();f.advance(400);f.tap();assert.equal(f.events.length,0,'slow taps stay separate');
f=fixture();f.tap(f.image,{duration:500});f.tap();assert.equal(f.events.length,0,'long press is not a tap');
f=fixture();f.tap();f.tap(f.image,{x:100});assert.equal(f.events.length,0,'distant taps stay separate');
for(const gesture of ['scroll','pinch','cancel']) {
 f=fixture();f.tap();f.emit('touchstart',f.image,{touches:[f.touch()]});
 if(gesture==='cancel')f.emit('touchcancel',f.image);
 else f.emit('touchmove',f.image,{touches:gesture==='pinch'?[f.touch(),f.touch(100,100)]:[f.touch(20,100)]});
 f.emit('touchend',f.image,{touches:[],changedTouches:[f.touch()]});assert.equal(f.events.length,0,gesture+' must not select a song');
}
for(const f of [fixture('https://accounts.spotify.com'),fixture('https://evil.test'),fixture('https://open.spotify.com',false)]) {
 f.tap();f.tap();assert.equal(f.events.length,0);assert.equal(f.styles.length,0,'login, other origins and mobile layout remain untouched');
}
console.log('PASS: manual double-tap, single taps, controls, mouse, duplicate events, recycled rows, scrolling, pinch, long press, cancellation and origin/layout scope');
