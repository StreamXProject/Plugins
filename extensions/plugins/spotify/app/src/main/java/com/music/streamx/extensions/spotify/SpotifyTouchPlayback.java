package com.music.streamx.extensions.spotify;

/** Maps manual thumbnail/row double-taps to Spotify's desktop double-click handler. */
final class SpotifyTouchPlayback {
    static String script() {
        return """
            (() => {
              if(location.origin!=='https://open.spotify.com' || !navigator.userAgent.includes('X11; Linux x86_64') || window.__streamxTouchPlayback)return;
              window.__streamxTouchPlayback=true;
              const selector='[data-testid="tracklist-row"]';
              const ignored='a,button,[role="button"],input,select,textarea,[contenteditable="true"]';
              const rowFor=target=>target?.closest && !target.closest(ignored)?target.closest(selector):null;
              const keyFor=row=>row.querySelector('a[href*="/track/"]')?.getAttribute('href')||row.textContent;
              const distance=(a,b)=>Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);
              let active=null,last=null,synthetic=null,dispatching=false;
              const installStyle=()=>{
                if(!document.head || document.getElementById('streamx-track-touch'))return;
                const style=document.createElement('style');style.id='streamx-track-touch';
                // manipulation preserves panning and pinch zoom, and excludes double-tap zoom.
                style.textContent=selector+' { touch-action: manipulation; }';document.head.appendChild(style);
              };
              installStyle();document.addEventListener('DOMContentLoaded',installStyle,{once:true});
              document.addEventListener('touchstart',event=>{
                const row=rowFor(event.target);
                if(event.touches.length!==1 || !row){active=null;last=null;return;}
                const touch=event.touches[0];
                active={row,key:keyFor(row),target:event.target,clientX:touch.clientX,clientY:touch.clientY,time:Date.now()};
              },{capture:true,passive:true});
              document.addEventListener('touchmove',event=>{
                if(active && (event.touches.length!==1 || distance(active,event.touches[0])>18)){active=null;last=null;}
              },{capture:true,passive:true});
              document.addEventListener('touchcancel',()=>{active=null;last=null;},{capture:true,passive:true});
              document.addEventListener('touchend',event=>{
                const tap=active;active=null;
                if(!tap || event.touches.length || event.changedTouches.length!==1){last=null;return;}
                const touch=event.changedTouches[0],now=Date.now();
                if(now-tap.time>350 || distance(tap,touch)>18 || tap.row.isConnected===false || keyFor(tap.row)!==tap.key){last=null;return;}
                if(!last || last.row!==tap.row || last.key!==tap.key || now-last.time>350 || distance(last,touch)>24){
                  last={row:tap.row,key:tap.key,clientX:touch.clientX,clientY:touch.clientY,time:now};return;
                }
                last=null;
                if(!event.cancelable)return;
                event.preventDefault(); // Suppress the second compatibility click/zoom gesture.
                synthetic={row:tap.row,time:now};dispatching=true;
                try {
                  tap.target.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,view:window,detail:2,clientX:touch.clientX,clientY:touch.clientY}));
                } finally {dispatching=false;}
              },{capture:true,passive:false});
              document.addEventListener('dblclick',event=>{
                if(!dispatching && synthetic && rowFor(event.target)===synthetic.row && Date.now()-synthetic.time<600
                    && event.sourceCapabilities?.firesTouchEvents!==false){event.preventDefault();event.stopImmediatePropagation();}
              },true);
            })();
            """;
    }
}
