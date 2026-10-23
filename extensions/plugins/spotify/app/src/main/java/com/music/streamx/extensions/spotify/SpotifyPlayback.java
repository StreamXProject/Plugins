package com.music.streamx.extensions.spotify;

/** Opens Spotify's own DRM-capable player; no protected audio is extracted. */
final class SpotifyPlayback {
    static String track(String id) { return id != null && id.matches("[A-Za-z0-9]{22}") ? id : null; }
    static String url(String id) {
        if (track(id) == null) throw new IllegalArgumentException("Invalid Spotify track ID");
        return "https://open.spotify.com/track/" + id;
    }
    static boolean matchesPath(String path, String id) {
        if (track(id) == null || path == null) return false;
        return path.matches("/(?:intl-[a-zA-Z-]+/)?track/" + id + "/?");
    }
    private static String guard(String id) {
        return "const parts=location.pathname.split('/').filter(Boolean); if(parts[0] && /^intl-[a-zA-Z-]+$/.test(parts[0])) parts.shift();"
            + "if(location.origin !== 'https://open.spotify.com' || parts.length!==2 || parts[0]!=='track' || parts[1]!=='" + id + "') return 'wrong page';";
    }
    /** Only the device-choice prompt is handled; login/consent Continue buttons are untouched. */
    static String continuePromptScript() { return continuePromptScript(false); }
    static String continuePromptScript(boolean localPlayback) {
        return "window.__streamxPreferLocal="+localPlayback+";" + """
            (() => {
              if(location.origin!=='https://open.spotify.com') return;
              if(window.__streamxContinuePrompt) {window.__streamxContinuePrompt(); return;}
              const clicked=new WeakMap();
              const normalize=s=>(s||'').trim().replace(/\\s+/g,' ').toLowerCase();
              window.__streamxContinuePrompt=() => {
                for(const button of document.querySelectorAll('button,[role="button"]')) {
                  if(button.disabled || button.getClientRects().length===0 || normalize(button.textContent)!=='continue') continue;
                  let container=button.parentElement;
                  for(let depth=0;container && container!==document.body && depth<7;depth++,container=container.parentElement) {
                    const text=normalize(container.textContent);
                    if(text.length>3500) break;
                    if(!text.includes('you are listening on')) continue;
                    const choices=[...container.querySelectorAll('button,[role="button"]')];
                    const local=choices.find(choice=>['listen on this phone','listen on this device','listen here','play here'].includes(normalize(choice.textContent)));
                    if(!local) continue;
                    const target=window.__streamxPreferLocal?local:button;
                    if(target.disabled || target.getClientRects().length===0) continue;
                    const now=Date.now();
                    if(now-(clicked.get(target)||0)<1000) break;
                    clicked.set(target,now); target.click();
                    if(window.__streamxPreferLocal)window.__streamxLocalActivations=(window.__streamxLocalActivations||0)+1;
                    window.__streamxContinueCount=(window.__streamxContinueCount||0)+1;
                    return;
                  }
                }
              };
              let scheduled=false;
              new MutationObserver(() => {
                if(scheduled) return; scheduled=true;
                queueMicrotask(() => {scheduled=false;window.__streamxContinuePrompt();});
              }).observe(document,{childList:true,subtree:true,characterData:true});
              window.__streamxContinuePrompt();
            })();
            """;
    }
    /** Use Spotify's device picker rather than controlling a different browser's session. */
    static String activateLocalScript() {
        return """
            (() => {
              if(location.origin!=='https://open.spotify.com')return 'wrong page';
              const remote=[...document.querySelectorAll('button')].some(b=>/^playing on /i.test((b.textContent||'').trim()));
              if(!remote)return 'local device ready';
              const now=Date.now();
              if(now-(window.__streamxDeviceAttempt||0)<3000)return 'waiting for local device';
              const row=[...document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]')]
                .find(row=>(row.querySelector('[data-testid="list-row-title"]')?.textContent||'').trim()==='This web browser');
              const local=row?.querySelector('[role="button"],button');
              if(local && !local.disabled && local.getAttribute('aria-disabled')!=='true') {
                window.__streamxDeviceAttempt=now;local.click();
                window.__streamxLocalActivations=(window.__streamxLocalActivations||0)+1;
              } else {
                const picker=document.querySelector('button[aria-label="Connect to a device"]');
                if(picker && picker.getAttribute('aria-pressed')!=='true')picker.click();
              }
              return 'waiting for local device';
            })()
            """;
    }
    static String transportScript(boolean play) {
        return "(() => { window.__streamxPaused=" + !play + "; const m=window.__streamxMedia || document.querySelector('audio'); if(m) window.__streamxMedia=m;"
            + (play ? "window.__streamxEnded=false;if(window.__streamxWantedTrack && (window.__streamxAwaitingSelection || window.__streamxCheckTrack?.()!==window.__streamxWantedTrack))return; const button=document.querySelector('button[data-testid=\"control-button-playpause\"]'); if(button && /^play\\b/i.test(button.getAttribute('aria-label')||'')) button.click(); if(m && m.paused) m.play().catch(() => {});"
                : "const button=document.querySelector('button[data-testid=\"control-button-playpause\"]');"
                + "if(button && /^pause\\b/i.test(button.getAttribute('aria-label')||'')) button.click();"
                + "const media=new Set([...(window.__streamxMediaElements||[]),...document.querySelectorAll('audio,video')]);"
                + "if(m) media.add(m); media.forEach(element => element.pause());")
            + " })()";
    }
    static String navigateScript(String id, boolean play, String title) {
        if (track(id) == null) throw new IllegalArgumentException("Invalid Spotify track ID");
        return "(() => { if(location.origin!=='https://open.spotify.com') return false;"
            + "window.__streamxWantedTrack=" + org.json.JSONObject.quote(id) + ";window.__streamxMatchedTrack=false;window.__streamxPaused=" + !play + "; window.__streamxAwaitingSelection=true; window.__streamxFreeShuffle=false; window.__streamxRoutePending=true;"
            + "window.__streamxWantedTitle=" + org.json.JSONObject.quote(title == null ? "" : title) + ";"
            + "window.__streamxPreviousTitle=(document.querySelector('[role=\"main\"] h1, main h1, h1')?.textContent||'').trim();"
            + "window.__streamxMedia=null; window.__streamxEnded=false; window.__streamxMediaError=null;"
            + "window.__streamxPlayRequested=null; window.__streamxPlayAttempt=0;window.__streamxSelectionControl=null;"
            + "const path='/track/" + id + "'; const link=[...document.querySelectorAll('a[href]')].find(a => a.getAttribute('href')===path);"
            + "if(link) { link.click(); return true; }"
            + "if(history.pushState) { history.pushState({...history.state,key:'streamx'+Date.now()},'',path); window.dispatchEvent(new PopStateEvent('popstate',{state:history.state})); }"
            + "return false; })()";
    }
    static String rendererScript(String id) {
        if (track(id) == null) throw new IllegalArgumentException("Invalid Spotify track ID");
        return "(() => { " + guard(id) + "window.__streamxWantedTrack=" + org.json.JSONObject.quote(id) + ";if(window.__streamxEnded) return 'ended';const actual=window.__streamxCheckTrack?.();if(window.__streamxEnded) return 'ended';" + "if(window.__streamxRoutePending){const normalize=s=>s.normalize('NFKC').trim().replace(/\\s+/g,' ').toLowerCase();"
            + "const headings=[...document.querySelectorAll('h1')].map(h=>(h.textContent||'').trim()).filter(Boolean);"
            + "const title=window.__streamxWantedTitle?headings.find(h=>normalize(h)===normalize(window.__streamxWantedTitle)):headings.find(h=>h!==window.__streamxPreviousTitle);"
            + "if(!title) return 'waiting for track page';"
            + "window.__streamxRoutePending=false;} window.__streamxPaused=false;"
            + "const device=" + activateLocalScript() + ";if(device==='waiting for local device')return device;"
            + "const audioEl=document.querySelector('audio'); const m=window.__streamxMedia || (audioEl?.tagName==='AUDIO'?audioEl:null); if(m) window.__streamxMedia=m;"
            + "const bottomBtn=document.querySelector('button[data-testid=\"control-button-playpause\"]');"
            + "const bottomPlaying=bottomBtn && /^pause\\b/i.test(bottomBtn.getAttribute('aria-label')||'');"
            + "const targetRunning=actual===window.__streamxWantedTrack && !window.__streamxAwaitingSelection;"
            + "const isRunning=(m && !m.paused && (m.readyState>=2 || m.currentTime>0) && targetRunning) || (bottomPlaying && targetRunning);"
            + "if(isRunning) { window.__streamxAwaitingSelection=false; return 'audio running'; }"
            // The page can expose Play before its player is ready after a cold start.
            // Retry an unaccepted click promptly; give an existing media element time to buffer.
            + "if(Date.now()-(window.__streamxPlayAttempt||0)<(m?8000:1500)) return 'waiting for audio';"
            + "if(m && m.paused && m.readyState>=1 && actual===window.__streamxWantedTrack && !window.__streamxAwaitingSelection) {window.__streamxPlayAttempt=Date.now(); m.play().catch(() => {}); return 'resuming audio';}"
            + "window.__streamxPlayRequested=null; const result=" + script(id) + ";"
            + "if(result==='clicked'||result==='already playing') window.__streamxPlayAttempt=Date.now(); return result; })()";
    }
    static String script(String id) {
        if (track(id) == null) throw new IllegalArgumentException("Invalid Spotify track ID");
        return "(() => { " + guard(id)
            + "const actual=window.__streamxCheckTrack?.();"
            + "const audioEl=document.querySelector('audio'); const m=window.__streamxMedia || (audioEl?.tagName==='AUDIO'?audioEl:null); if(m) window.__streamxMedia=m;"
            + "const bottomBtn=document.querySelector('button[data-testid=\"control-button-playpause\"]');"
            + "const bottomPlaying=bottomBtn && /^pause\\b/i.test(bottomBtn.getAttribute('aria-label')||'');"
            + "const targetRunning=actual==='" + id + "' && !window.__streamxAwaitingSelection;"
            + "if((m && !m.paused && (m.readyState>=2 || m.currentTime>0) && targetRunning) || (bottomPlaying && targetRunning)) { window.__streamxAwaitingSelection=false; return 'already playing'; }"
            + "if(window.__streamxPlayRequested === '" + id + "') return 'already requested';"
            + "const rowResult=" + selectRowScript(id) + ";if(rowResult!=='missing row')return rowResult;"
            // Only the requested track page's own action bar is a valid fallback.
            // A generic/top-bar Play resumes a previous context, even after following a title link.
            + "const button=document.querySelector('[data-testid=\"action-bar-row\"] button[data-testid=\"play-button\"], [data-testid=\"entity-play-button\"] button[data-testid=\"play-button\"]');"
            + "if(!button || button.disabled) return 'waiting';"
            + "if(/^pause\\b/i.test(button.getAttribute('aria-label')||'') || button.querySelector('[data-testid=\"pause-icon\"]')) return 'waiting for audio';"
            + "window.__streamxAwaitingSelection=false;window.__streamxPlayRequested='" + id + "';window.__streamxSelectionControl='track-page-play';button.click();return 'clicked'; })()";
    }
    /** Select a recording through its row control; title links only navigate. */
    static String selectRowScript(String id) {
        if (track(id) == null) throw new IllegalArgumentException("Invalid Spotify track ID");
        return """
            (() => {
              if(location.origin!=='https://open.spotify.com')return 'wrong page';
              const id='__TRACK_ID__';
              const trackId=href=>{
                try {
                  const url=new URL(href,location.origin);
                  if(url.origin!==location.origin)return null;
                  return url.pathname.match(/^\\/(?:intl-[a-zA-Z-]+\\/)?track\\/([A-Za-z0-9]{22})\\/?$/)?.[1]||null;
                }catch(_){return null;}
              };
              const row=[...document.querySelectorAll('[data-testid="tracklist-row"], [role="row"]')]
                .find(row=>[...row.querySelectorAll('a[href]')].some(link=>trackId(link.getAttribute('href'))===id));
              if(!row)return 'missing row';
              const controls=[...row.querySelectorAll('button,[role="button"]')];
              const button=controls.find(b=>/^play\\b/i.test(b.getAttribute('aria-label')||''))
                || controls.find(b=>b.getAttribute('data-testid')==='play-button');
              if(button && (button.disabled || button.getAttribute('aria-disabled')==='true'))return 'waiting';
              if(button && (/^pause\\b/i.test(button.getAttribute('aria-label')||'') || button.querySelector('[data-testid="pause-icon"]')))
                return 'waiting for audio';
              if(!button && controls.some(b=>/^pause\\b/i.test(b.getAttribute('aria-label')||'')))return 'waiting for audio';
              if(!button && typeof MouseEvent==='undefined')return 'waiting';
              window.__streamxAwaitingSelection=false;
              window.__streamxPlayRequested=id;
              if(button){window.__streamxSelectionControl='row-play';button.click();}
              else {
                window.__streamxSelectionControl='row-double-click';
                (row.querySelector('img')||row).dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,view:window,detail:2}));
              }
              return 'clicked';
            })()
            """.replace("__TRACK_ID__",id);

    }
}
