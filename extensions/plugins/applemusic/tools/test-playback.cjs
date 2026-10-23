const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync(`${__dirname}/../app/src/main/assets/playback.js`,'utf8');
function player(clock=Date){
 const calls=[];
 const music={volume:1,nowPlayingItem:null,currentPlaybackTime:0,currentPlaybackDuration:200,isPlaying:false,playbackState:0,
  pause(){calls.push('pause');this.isPlaying=false;return Promise.resolve()},stop(){this.isPlaying=false;return Promise.resolve()},
  setQueue({song}){calls.push(`queue:${song}`);this.nowPlayingItem={id:song};return Promise.resolve()},
  play(){calls.push('play');this.isPlaying=true;return Promise.resolve()},seekToTime(t){calls.push(`seek:${t}`);this.currentPlaybackTime=t;return Promise.resolve()}};
 const window={MusicKit:{getInstance:()=>music,PlaybackStates:{playing:2,ended:5}}};
 const context=vm.createContext({window,Promise,Date:clock,Number,String,Math,setTimeout,clearTimeout});
 return {music,calls,window,run:(id='123',gen=1,play=true,quality=256,station=false)=>vm.runInContext(source.replace('__WANTED__',JSON.stringify(id)).replace('__GENERATION__',gen).replace('__REQUESTED__',play).replace('__QUALITY__',quality).replace('__STATION__',station),context)};
}
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve()};
(async()=>{
 const p=player();assert.equal(p.run().state,'buffering');await settle();p.run();await settle();p.run();
 assert.equal(p.music.nowPlayingItem.id,'123');assert.equal(p.music.volume,1);assert.equal(p.calls.filter(c=>c==='play').length,1);
 p.music.currentPlaybackTime=.4;assert.equal(p.run().state,'playing');assert.equal(p.run().position_ms,400);
 p.window.__bitChordApplePlayer.command('pause',0);await settle();assert.equal(p.run('123',1,false).state,'paused');
 p.window.__bitChordApplePlayer.command('seek',45000);p.run('123',1,false);await settle();assert.ok(p.calls.includes('seek:45'));
 p.window.__bitChordApplePlayer.command('play',0);p.run();await settle();assert.equal(p.music.isPlaying,true);
 const plays=p.calls.filter(c=>c==='play').length;p.run();p.run();assert.equal(p.calls.filter(c=>c==='play').length,plays);
 p.music.currentPlaybackTime=200;p.music.isPlaying=false;p.music.playbackState=5;assert.equal(p.run().state,'ended');
 p.music.nowPlayingItem=null;p.music.playbackState=10;p.window.MusicKit.PlaybackStates.completed=10;p.music.currentPlaybackTime=0;p.music.currentPlaybackDuration=0;
 assert.equal(p.run().state,'ended');assert.equal(p.run().duration_ms,200000);
 const q=player();q.run('old',1);q.run('new',2);await settle();q.run('new',2);await settle();assert.deepEqual(q.calls.filter(c=>c.startsWith('queue:')),['queue:new']);
 q.music.nowPlayingItem={id:'wrong'};q.music.isPlaying=true;assert.equal(q.run('new',2).state,'error');assert.equal(q.music.volume,0);
 const pending=player();pending.music.queue={items:[]};
 pending.music.setQueue=({song})=>{pending.music.queue.items=[{id:song}];return Promise.resolve()};
 pending.music.play=()=>{pending.calls.push('play');pending.music.nowPlayingItem=pending.music.queue.items[0];pending.music.isPlaying=true;return Promise.resolve()};
 pending.run();await settle();assert.equal(pending.music.nowPlayingItem,null);pending.run();await settle();pending.music.currentPlaybackTime=.4;assert.equal(pending.run().state,'playing');
 const paused=player();paused.run();await settle();paused.run('123',1,false);await settle();assert.ok(!paused.calls.includes('play'));
 const library=player();library.music.api={music:async()=>({data:{data:[{id:'i.owned',attributes:{playParams:{catalogId:'123'}}}]}})};
 library.music.setQueue=({items})=>{library.music.nowPlayingItem=items[0];return Promise.resolve()};
 library.run('i.owned');await settle();library.run('i.owned');await settle();library.music.currentPlaybackTime=.4;assert.equal(library.run('i.owned').state,'playing');
 const missing=player();missing.music.api={music:async()=>({data:{data:[{id:'i.different'}]}})};missing.run('i.owned');await settle();assert.match(missing.run('i.owned').error,/no longer/);assert.ok(!missing.calls.includes('play'));
 const transition=player();const events=new Map();transition.music.playbackState=6;
 transition.music.addEventListener=(name,fn)=>events.set(name,fn);transition.music.removeEventListener=name=>events.delete(name);
 transition.run();await settle();assert.ok(!transition.calls.some(c=>c.startsWith('queue:')));
 transition.music.playbackState=3;events.get('playbackStateDidChange')();await settle();assert.ok(transition.calls.includes('queue:123'));
 const restricted=player();restricted.music.setQueue=()=>Promise.reject({name:'CONTENT_RESTRICTED'});restricted.run();await settle();assert.match(restricted.run().error,/restricted/);
 const low=player();low.run('123',1,true,64);await settle();assert.equal(low.music.bitrate,64);assert.equal(low.run('123',1,true,64).requested_bitrate_kbps,64);
 low.music.currentPlaybackTime=.4;low.run('123',1,true,256);assert.equal(low.music.bitrate,64); // quality is latched per load
 low.run('123',2,true,256);await settle();assert.equal(low.music.bitrate,256);
 const invalid=player();invalid.run('123',1,true,320);await settle();assert.match(invalid.run('123',1,true,320).error,/Unsupported/);assert.ok(!invalid.calls.some(c=>c.startsWith('queue:')));
 let now=1000;const stuck=player({now:()=>now});stuck.run();await settle();stuck.run();await settle();stuck.music.currentPlaybackTime=.4;stuck.run();
 now+=16000;stuck.run();await settle();assert.equal(stuck.window.__bitChordApplePlayer.recoveryCount,1);assert.equal(stuck.music.nowPlayingItem.id,'123');
 now+=16000;assert.match(stuck.run().error,/stopped advancing/);assert.equal(stuck.calls.filter(c=>c.startsWith('queue:')).length,1);
 const unresolved=player();unresolved.music.currentPlaybackDuration=Infinity;assert.equal(unresolved.run().duration_ms,0);
 const slow=player();let releasePause;let elementPaused=false;
 slow.window.document={querySelectorAll:()=>[{pause:()=>{elementPaused=true}}]};
 slow.run();await settle();slow.run();await settle();slow.music.currentPlaybackTime=.4;slow.run();
 slow.music.pause=()=>new Promise(resolve=>{releasePause=()=>{slow.music.isPlaying=false;resolve()}});
 slow.window.__bitChordApplePlayer.command('pause',0);
 assert.equal(slow.music.volume,0);assert.equal(elementPaused,true);assert.equal(slow.run('123',1,false).state,'paused');
 const before=slow.calls.filter(c=>c==='play').length;
 slow.window.__bitChordApplePlayer.command('play',0);slow.run();await settle();assert.equal(slow.calls.filter(c=>c==='play').length,before);
 releasePause();await settle();slow.run();await settle();assert.equal(slow.calls.filter(c=>c==='play').length,before+1);
 const radio=player();radio.music.setQueue=({station})=>{radio.calls.push(`station:${station}`);radio.music.nowPlayingItem={id:'broadcast-song-1'};return Promise.resolve()};
 radio.run('ra.discovery',1,true,256,true);await settle();radio.run('ra.discovery',1,true,256,true);await settle();radio.music.currentPlaybackTime=.4;
 assert.equal(radio.run('ra.discovery',1,true,256,true).state,'playing');assert.equal(radio.run('ra.discovery',1,true,256,true).duration_ms,200000);
 assert.ok(radio.calls.includes('station:ra.discovery'));
 radio.music.nowPlayingItem={id:'broadcast-song-2',attributes:{name:'New song',artistName:'Artist',albumName:'Album',durationInMillis:184000,artwork:{url:'https://example.com/{w}x{h}.jpg'}}};radio.music.currentPlaybackTime=.8;
 const broadcast=radio.run('ra.discovery',1,true,256,true);
 assert.equal(broadcast.duration_ms,184000);assert.equal(broadcast.position_ms,800);
 assert.equal(broadcast.now_playing.title,'New song');assert.equal(broadcast.now_playing.thumbnail_url,'https://example.com/600x600.jpg');
 radio.window.__bitChordApplePlayer.command('seek',42000);radio.run('ra.discovery',1,true,256,true);await settle();assert.ok(radio.calls.includes('seek:42'));
 radio.music.nowPlayingItem={id:'broadcast-song-3',attributes:{name:'Third song'}};radio.music.currentPlaybackDuration=0;radio.music.currentPlaybackTime=.9;
 assert.equal(radio.run('ra.discovery',1,true,256,true).duration_ms,0); // no preceding song's runtime

 assert.equal(radio.run('ra.discovery',1,true,256,true).state,'playing');
 radio.window.__bitChordApplePlayer.command('pause',0);await settle();assert.equal(radio.run('ra.discovery',1,false,256,true).state,'paused');
 const r=player();r.music.setQueue=()=>Promise.reject(new Error('unavailable'));r.run();await settle();assert.equal(r.run().error,'unavailable');assert.equal(r.music.volume,0);
 console.log('Playback: identity, actual clock, pause/resume, seek, one play per command, completion, stale load and error checks passed');
})().catch(e=>{console.error(e);process.exitCode=1});
