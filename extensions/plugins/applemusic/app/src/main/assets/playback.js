/* Runs only on music.apple.com using Apple's own configured MusicKit player.
 * Library item descriptors and transition handling adapted from Vibez (MIT).
 * See licenses/vibez.txt. No desktop runtime or bundled token is required.
 */
((wanted, generation, requested, quality, station) => {
  let music;
  try { music=window.MusicKit?.getInstance(); } catch (_) {}
  if(!music) return {state:'buffering',play_control:'Loading Apple Music'};
  let state=window.__bitChordApplePlayer;
  if(!state || state.generation!==generation) {
    state={generation,wanted,desired:requested,ready:false,started:false,playIssued:false,error:null,phase:'Selecting track',lastTime:0,lastAdvance:0,heard:false,matched:false,lastDuration:0,pendingSeek:null,recoveryCount:0,recovering:false,pausing:false,pausePromise:null};
    const previous=window.__bitChordApplePlayer;
    previous?.cleanup?.();
    window.__bitChordApplePlayer=state;
    const live=()=>window.__bitChordApplePlayer===state;
    state.errorText=e=> {
      const code=String(e?.name||'');
      if(/AUTHORIZATION|UNAUTHORIZED|EXPIRED/i.test(code))return 'Apple Music session expired. Open your account and sign in again.';
      if(/SUBSCRIPTION/i.test(code))return 'An active Apple Music subscription is required for full-track streaming.';
      if(/CONTENT_RESTRICTED/i.test(code))return 'Apple Music restricted this track for your account. Check content restrictions in Apple Music.';
      if(/CONTENT_UNAVAILABLE|CONTENT_EQUIVALENT|NOT_FOUND/i.test(code))return 'This recording is unavailable for your Apple Music account or region.';
      return String(e?.message||e||'Apple Music playback failed').replace(/eyJ[A-Za-z0-9_.-]+/g,'[redacted]').slice(0,300);
    };
    const authorization=()=>{if(live() && music.isAuthorized===false)state.error='Apple Music session expired. Open your account and sign in again.';};
    const playbackError=event=>{if(live())state.error=state.errorText(event?.error||event);};
    music.addEventListener?.('authorizationStatusDidChange',authorization);
    music.addEventListener?.('mediaPlaybackError',playbackError);
    state.cleanup=()=>{music.removeEventListener?.('authorizationStatusDidChange',authorization);music.removeEventListener?.('mediaPlaybackError',playbackError);};
    // Retained browser loads serialize; callbacks from an older queue cannot unmute it.
    window.__bitChordAppleLoads=(window.__bitChordAppleLoads||Promise.resolve()).catch(()=>{}).then(async()=>{
      if(!live())return;
      try {
        if(![64,256].includes(quality))throw new Error('Unsupported Apple Music quality');
        const rates=window.MusicKit.PlaybackBitrate||{};
        music.bitrate=quality===64?(rates.STANDARD||64):(rates.HIGH||256);
        state.quality=quality;
        music.volume=0; try { await music.pause(); } catch(_) {}
        try { music.shuffleMode=0; music.repeatMode=0; music.autoplayEnabled=false; } catch(_) {}
        // Vibez waits for an in-flight seek before replacing the queue. Keep this bounded.
        if([6,7].includes(music.playbackState))await new Promise(resolve=>{
          const done=()=>{if(![6,7].includes(music.playbackState)){music.removeEventListener?.('playbackStateDidChange',done);clearTimeout(timer);resolve();}};
          const timer=setTimeout(()=>{music.removeEventListener?.('playbackStateDidChange',done);resolve();},200);
          music.addEventListener?.('playbackStateDidChange',done);
        });
        if(!live())return;
        if(station) {
          await music.setQueue({station:wanted});
          if(live())state.stationSelected=true;
        } else if(wanted.startsWith('i.')) {
          const result=await music.api.music('/v1/me/library/songs',{ids:[wanted]});
          if(!live())return;
          const resource=result?.data?.data?.find(row=>row.id===wanted);
          if(!resource)throw new Error('This song is no longer in your Apple Music library.');
          state.catalogueId=resource.attributes?.playParams?.catalogId||'';
          await music.setQueue({items:[resource]});
        } else await music.setQueue({song:wanted});
        if(!live())return;
        state.ready=true;state.phase='Waiting for selected track';
      } catch(e) { if(live())state.error=state.errorText(e); }
    });
    state.command=(command,position)=>{
      if(!live())return;
      if(command==='pause') {
        state.desired=false;state.playIssued=false;state.lastAdvance=Date.now();music.volume=0;
        // Stop output immediately; MusicKit's asynchronous state transition may lag the media element.
        window.document?.querySelectorAll('audio,video').forEach(element=>element.pause());
        state.pausing=true;
        const operation=Promise.resolve(music.pause()).catch(e=>{if(live())state.error=state.errorText(e);});
        state.pausePromise=operation;
        operation.finally(()=>{if(state.pausePromise===operation){state.pausing=false;state.playIssued=false;}});
      }
      else if(command==='play') {state.desired=true;state.playIssued=false;state.lastAdvance=Date.now();}
      else if(command==='seek') {state.pendingSeek=Math.max(0,Number(position)||0)/1000;state.lastAdvance=Date.now();}
      else if(command==='stop'){state.cleanup();state.desired=false;music.volume=0;Promise.resolve(music.stop()).catch(()=>{});}
    };
  }
  state.desired=requested;
  const item=music.nowPlayingItem||music.player?.nowPlayingItem;
  const identity=item=>String(item?.attributes?.playParams?.catalogId||item?.playParams?.catalogId||item?.id||item?.attributes?.playParams?.id||'');
  const accepts=item=>!!item && (String(item.id)===wanted || identity(item)===wanted || (state.catalogueId && identity(item)===state.catalogueId));
  const observed=identity(item);
  const queueMatches=accepts(music.queue?.items?.[0]);
  const matches=station ? state.stationSelected===true && !!item : accepts(item);
  const finite=value=>Number.isFinite(Number(value))?Math.max(0,Number(value)):0;
  const time=finite(music.currentPlaybackTime);
  const attributes=item?.attributes||item||{};
  const songChanged=station && matches && observed!==state.broadcastId;
  if(songChanged) {
    state.broadcastId=observed;state.lastDuration=0;state.lastTime=time;
    state.lastAdvance=Date.now();state.recoveryCount=0;
  }
  // A station has no end, but its current recording has its own finite clock.
  const duration=station ? finite(attributes.durationInMillis)/1000 || finite(music.currentPlaybackDuration) : finite(music.currentPlaybackDuration);
  const artwork=attributes.artwork?.url||item?.artwork?.url||'';
  const nowPlaying=station && matches && attributes.name ? {
    id:observed,title:String(attributes.name).slice(0,500),artist:String(attributes.artistName||'').slice(0,500),
    album:String(attributes.albumName||'').slice(0,500),
    thumbnail_url:String(artwork).replace('{w}','600').replace('{h}','600'),
  } : null;
  const states=window.MusicKit?.PlaybackStates||{};
  const playing=music.isPlaying===true || (states.playing!==undefined && music.playbackState===states.playing);
  if(state.ready && state.desired && !state.pausing && !state.playIssued && !state.error && (matches || queueMatches || (station && state.stationSelected))) {
    state.playIssued=true;state.phase='Starting Apple Music audio';music.volume=0;
    Promise.resolve(music.play()).then(()=>{if(window.__bitChordApplePlayer===state){state.started=true;if(!state.desired){music.volume=0;window.document?.querySelectorAll('audio,video').forEach(element=>element.pause());}}}).catch(e=>state.error=state.errorText(e));
  }
  if(state.ready && matches) {
    if(state.pendingSeek!==null) {
      const seek=state.pendingSeek;state.pendingSeek=null;
      Promise.resolve(music.seekToTime(seek)).catch(e=>state.error=state.errorText(e));
    }
    if(state.desired && playing && !state.error)music.volume=1;
    if(playing && time>state.lastTime && time-state.lastTime<2) {state.heard=true;state.lastAdvance=Date.now();state.phase='Playing selected track';}
  } else if(playing) {
    music.volume=0;Promise.resolve(music.pause()).catch(()=>{});
    if(state.ready && state.started && observed) state.error='Apple Music selected a different track. Please retry.';
  }
  // Sidra also watches the real clock, since MusicKit can report playing while stalled.
  // Recover the same recording once; do not silently skip the user's selection.
  if(state.desired && matches && state.heard && !state.error && !state.recovering &&
      Date.now()-state.lastAdvance>15000 && (duration===0 || duration-time>10)) {
    if(state.recoveryCount>=1)state.error='Apple Music stopped advancing. Retry this track.';
    else {
      state.recoveryCount++;state.recovering=true;state.lastAdvance=Date.now();state.phase='Recovering stalled Apple Music audio';
      Promise.resolve(music.pause()).then(()=>{
        if(window.__bitChordApplePlayer!==state || !state.desired)return;
        return Promise.resolve(music.seekToTime(time)).then(()=>{
          if(window.__bitChordApplePlayer===state && state.desired)return music.play();
        });
      }).catch(e=>{if(window.__bitChordApplePlayer===state)state.error=state.errorText(e);})
        .finally(()=>{state.recovering=false;});
    }
  }
  if(matches){state.matched=true;if(duration>0)state.lastDuration=duration;}
  const previousTime=state.lastTime;
  state.lastTime=time;
  const ended=!station && state.heard && (matches || (!observed && state.matched)) && ((states.ended!==undefined && music.playbackState===states.ended) || (states.completed!==undefined && music.playbackState===states.completed) || (!playing && duration>0 && time>=duration-.25));
  return {state:state.error?'error':ended?'ended':!state.desired?'paused':matches&&playing&&state.heard&&Date.now()-state.lastAdvance<2500?'playing':'buffering',
    position_ms:Math.round((ended?Math.max(previousTime,time):time)*1000),duration_ms:Math.round((duration||state.lastDuration)*1000),now_playing:nowPlaying,buffered_ms:Math.round(time*1000),
    observed_track_id:observed,play_control:state.phase,error:state.error,backend:'web',mime_type:null,requested_bitrate_kbps:state.quality};
})(__WANTED__,__GENERATION__,__REQUESTED__,__QUALITY__,__STATION__)
