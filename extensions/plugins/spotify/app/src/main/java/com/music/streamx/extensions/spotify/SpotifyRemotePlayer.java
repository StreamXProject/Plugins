package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Private browser renderer controlled by the host's foreground media session. */
final class SpotifyRemotePlayer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WebView browser;
    private static Context rendererContext;
    private static androidx.webkit.ScriptHandler startupScript;
    private static String track;
    private static IBinder owner;
    private static IBinder.DeathRecipient death;
    private static long started;
    private static long adWaitMs,lastAdSample;
    private static long generation;
    private static long accountGeneration;
    private static boolean startupRecovered;
    private static boolean warmStart;
    private static long recoveredAt;
    private static final SpotifySelectionGate selectionGate=new SpotifySelectionGate();
    private static String recoveryReason="";
    private static boolean audioStarted;
    private static String lastTrace;
    private static long controlSequence;
    private static long controlLatency = -1;
    private static String controlName = "";
    private static boolean requested;
    private static String control = "not attempted";
    private static String pageFailure;
    private static long pendingSeek = -1;
    private static volatile String snapshot = "{\"state\":\"idle\",\"position_ms\":0,\"duration_ms\":0}";
    private static final String HOOK = """
        (() => {
          if(window.__streamxMediaHook) return;
          window.__streamxMediaHook=true;
          // Read only playing-item metadata/capabilities from Spotify's own UI store.
          // Mobile's mini-player has no track link; the route identifies a context, not its current song.
          window.__streamxReadPlayback=() => {
            try {
              let store=window.__streamxPlaybackStore;
              if(!store) {
                const element=document.querySelector('[data-testid="floating-now-playing-bar"], [data-testid="now-playing-widget"], [data-testid="entity-play-button"] button');
                let fiber=element?.[Object.keys(element).find(k=>k.startsWith('__reactFiber'))];
                for(let depth=0;fiber && depth<80;depth++,fiber=fiber.return) {
                  const candidate=fiber.memoizedProps?.value?.store;
                  if(typeof candidate?.getState==='function' && candidate.getState()?.playback) {store=candidate;window.__streamxPlaybackStore=store;break;}
                }
              }
              return store?.getState()?.playback?.current||null;
            } catch(_) {return null;}
          };
          window.__streamxIsAdvertisement=() => {
            const item=window.__streamxReadPlayback()?.track_window?.current_track;
            return !!(item?.uri?.startsWith('spotify:ad:') || [item?.type,item?.track_type,item?.content_type].some(v=>/^(ad|advertisement)$/i.test(v||'')));
          };
          window.__streamxObservedTrack=() => {
            const item=window.__streamxReadPlayback()?.track_window?.current_track;
            const linked=item?.linked_from?.uri;
            if(linked==='spotify:track:'+window.__streamxWantedTrack)return window.__streamxWantedTrack;
            const id=item?.uri?.match(/^spotify:track:([A-Za-z0-9]{22})$/)?.[1];
            if(id)return id;
            const links=[...document.querySelectorAll('[data-testid="now-playing-widget"] a[href*="/track/"], [data-testid="now-playing-bar"] a[href*="/track/"]')];
            const contextLink=document.querySelector('[data-testid="context-item-link"]');
            if(contextLink)links.push(contextLink);
            for(const link of links) {
              const match=(link.getAttribute('href')||'').match(/(?:^|\\/)(?:intl-[a-zA-Z-]+\\/)?track\\/([A-Za-z0-9]{22})(?:[?/#]|$)/);
              if(match)return match[1];
            }
            return null;
          };
          window.__streamxObservedTitle=() => window.__streamxReadPlayback()?.track_window?.current_track?.name
            || ([...document.querySelectorAll('[data-testid="now-playing-widget"] a[href]')].find(a=>(a.textContent||'').trim())?.textContent
              || document.querySelector('[data-testid="floating-now-playing-bar"] span')?.textContent)?.trim()||null;
          const mutedProperty=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'muted');
          if(mutedProperty?.get && mutedProperty?.set) Object.defineProperty(HTMLMediaElement.prototype,'muted',{
            configurable:true,enumerable:mutedProperty.enumerable,get:mutedProperty.get,
            set(value){mutedProperty.set.call(this,this.tagName==='AUDIO' && this.__streamxIdentityMuted?true:value);}
          });
          window.__streamxCheckTrack=() => {
            const normalize=s=>(s||'').normalize('NFKC').trim().replace(/\\s+/g,' ').toLowerCase();
            const title=window.__streamxObservedTitle();
            const advertisement=window.__streamxIsAdvertisement();
            const actual=advertisement?null:window.__streamxObservedTrack() || (title && window.__streamxWantedTitle
              ? (normalize(title)===normalize(window.__streamxWantedTitle)?window.__streamxWantedTrack:'title mismatch'):null);
            const verified=advertisement || ((actual===window.__streamxWantedTrack) && !window.__streamxAwaitingSelection);
            // Spotify may publish the previous mini-player title until play() starts.
            // Let startup complete silently; only a matching identity may become audible.
            for(const media of window.__streamxMediaElements||[]) {
              if(media.tagName!=='AUDIO') continue;
              if(!verified) {
                if(!media.__streamxIdentityMuted)media.__streamxWasMuted=media.muted;
                media.__streamxIdentityMuted=true;media.muted=true;
              } else if(media.__streamxIdentityMuted) {
                media.__streamxIdentityMuted=false;media.muted=media.__streamxWasMuted||false;
              }
            }
            // A remote device can already display the requested song before this
            // renderer starts. Only local, ready playback arms auto-next detection.
            const local=window.__streamxMedia || document.querySelector('audio');
            if(actual && actual===window.__streamxWantedTrack && !window.__streamxAwaitingSelection
                && local?.tagName==='AUDIO' && (local.readyState>=2 || local.currentTime>0) && !local.paused)
              window.__streamxMatchedTrack=true;
            if(actual && window.__streamxMatchedTrack && actual!==window.__streamxWantedTrack) {
              window.__streamxEnded=true; window.__streamxPaused=true;
              (window.__streamxMediaElements||[]).forEach(m=>m.pause());
            }
            return actual;
          };
          let identityPending=false;
          new MutationObserver(() => {
            if(identityPending) return;identityPending=true;
            queueMicrotask(() => {identityPending=false;window.__streamxCheckTrack();});
          }).observe(document,{childList:true,subtree:true,attributes:true,characterData:true,attributeFilter:['href']});
          if(window.MediaSource) {
            const add=MediaSource.prototype.addSourceBuffer;
            MediaSource.prototype.addSourceBuffer=function(type) {
              const buffer=add.apply(this,arguments);
              if(type.startsWith('audio/')) window.__streamxAudioType=type;
              return buffer;
            };
          }
          const play=HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play=function() {
            // Canvas can start before the song on a track switch. It is never an audio clock.
            if(this.tagName==='AUDIO') window.__streamxMedia=this;
            (window.__streamxMediaElements ||= new Set()).add(this);
            this.__streamxAdMedia=window.__streamxIsAdvertisement();
            const actual=window.__streamxCheckTrack();
            // Spotify primes its local decoder before the selection click settles.
            // Identity muting above keeps that startup inaudible; blocking play()
            // here can prevent its player from ever completing initialization.
            if(window.__streamxEnded === true || window.__streamxPaused === true) { this.pause(); return Promise.resolve(); }
            if(!this.__streamxEndedListener) {
              this.__streamxEndedListener=true;
              this.addEventListener('ended',() => {if(this.__streamxAdMedia || window.__streamxIsAdvertisement())return;if(window.__streamxMedia===this) {window.__streamxEnded=true;window.__streamxPaused=true;(window.__streamxMediaElements||[]).forEach(m=>m.pause());}});
            }
            const result=play.apply(this,arguments);
            if(result && result.then) result.then(() => {if(window.__streamxMedia===this) window.__streamxMediaError=null;},failure => {if(window.__streamxMedia===this) window.__streamxMediaError='Spotify browser play rejected: '+(['NotAllowedError','NotSupportedError','AbortError'].includes(failure?.name)?failure.name:'unknown');});
            return result;
          };
        })();
        """;
    static String call(Context context, Bundle args) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Browser playback commands must run off the UI thread");
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        Exception[] error = new Exception[1];
        MAIN.post(() -> {
            try {
                String command = args.getString("command", "status");
                if (command.equals("load")) load(context, args.getString("track_id"), args.getBinder("owner"), args.getBoolean("play",true), args.getString("track_title",""));
                else if (browser != null && args.getBinder("owner") != owner) throw new IllegalStateException("Spotify playback belongs to a different host session");
                else if (command.equals("stop")) destroy();
                else if (browser != null && (command.equals("play") || command.equals("pause") || command.equals("seek") || command.equals("next") || command.equals("previous"))) {
                    if (command.equals("seek")) {
                        long position = Math.max(0, args.getLong("position_ms", 0));
                        pendingSeek = position;
                    } else if (command.equals("next")) {
                        browser.evaluateJavascript("(() => { const next=document.querySelector('button[data-testid=\"control-button-skip-next\"]'); if(next && !next.disabled){next.click();return true;} return false; })()", null);
                    } else if (command.equals("previous")) {
                        browser.evaluateJavascript("(() => { const prev=document.querySelector('button[data-testid=\"control-button-skip-back\"]'); if(prev && !prev.disabled){prev.click();return true;} return false; })()", null);
                    } else {
                        boolean play = command.equals("play");
                        if(play && !requested && !audioStarted) started=System.currentTimeMillis();
                        requested = play;
                        long queuedAt=args.getLong("queued_at",android.os.SystemClock.elapsedRealtime());
                        long commandGeneration=generation, sequence=++controlSequence;
                        controlLatency=-1; controlName=command;
                        browser.evaluateJavascript(SpotifyPlayback.transportScript(play), raw -> {
                            if(generation==commandGeneration && controlSequence==sequence)
                                controlLatency=Math.max(0,android.os.SystemClock.elapsedRealtime()-queuedAt);
                        });
                    }
                }
                result[0] = snapshot;
            } catch (Exception failure) {
                context.getSharedPreferences("spotify_session",0).edit().putString("playback_trace", "Bridge command failed: " + failure.getClass().getSimpleName()).apply();
                error[0] = failure;
            }
            finally { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Spotify browser command timed out");
        if (error[0] != null) throw error[0];
        return result[0];
    }
    private static void load(Context context, String id, IBinder binder, boolean play, String title) throws Exception {
        if (SpotifyPlayback.track(id) == null || binder == null || !binder.isBinderAlive()) throw new IllegalArgumentException("Invalid Spotify playback request");
        context.getSharedPreferences("spotify_session",0).edit().putString("playback_trace", "Load command received; checking signed-in session").apply();
        if (!context.getSharedPreferences("spotify_session", 0).getBoolean("verified", false)) throw new IllegalStateException("Sign in to Spotify first");
        String cookies=CookieManager.getInstance().getCookie("https://open.spotify.com/");
        if(cookies==null || !java.util.regex.Pattern.compile("(?:^|;\\s*)sp_dc=[^;\\s]+").matcher(cookies).find())
            throw new IllegalStateException("Spotify login cookie is missing. Sign in again in the extension.");
        if (id.equals(track) && browser != null && binder == owner) { requested=play; return; }
        long sessionGeneration=context.getSharedPreferences("spotify_session",0).getLong("generation",0);
        if(browser!=null && binder==owner && accountGeneration==sessionGeneration && pageFailure==null) {
            WebView view=browser;
            warmStart=true;
            installStartupScript(view,id,title);
            view.evaluateJavascript(SpotifyPlayback.transportScript(false), null);
            track=id; requested=play; started=System.currentTimeMillis(); adWaitMs=0;lastAdSample=0; control="warm navigation";
            pendingSeek=-1; startupRecovered=false; recoveredAt=0; selectionGate.reset(); recoveryReason=""; audioStarted=false; lastTrace=null; controlLatency=-1;
            long loadGeneration=++generation;
            snapshot="{\"state\":\"buffering\",\"position_ms\":0,\"duration_ms\":0,\"track_id\":\"" + id + "\"}";
            view.evaluateJavascript(SpotifyPlayback.navigateScript(id,play,title), raw -> {
                if(browser==view && generation==loadGeneration && !"true".equals(raw)) {
                    startupRecovered=true; view.loadUrl(SpotifyPlayback.url(id));
                }
            });
            poll(view,id,loadGeneration);
            return;
        }
        destroy();
        rendererContext=context.getApplicationContext();
        accountGeneration=sessionGeneration;
        track=id; owner=binder; requested=play; started=System.currentTimeMillis(); adWaitMs=0;lastAdSample=0; control="cold startup"; pageFailure=null; pendingSeek=-1;
        warmStart=false; startupRecovered=false; recoveredAt=0; selectionGate.reset(); recoveryReason=""; audioStarted=false; lastTrace=null;
        long loadGeneration=++generation;
        death=() -> MAIN.post(() -> { if(owner==binder) destroy(); }); owner.linkToDeath(death, 0);
        snapshot="{\"state\":\"buffering\",\"position_ms\":0,\"duration_ms\":0,\"track_id\":\"" + id + "\"}";
        browser=new WebView(context.getApplicationContext());
        WebView view=browser;
        view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false); view.getSettings().setAllowContentAccess(false);
        SpotifyBrowser.configureRenderer(view);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,true);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { SpotifyBrowser.permission(request); }
        });
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            destroy(); throw new IllegalStateException("Update Android System WebView for Spotify playback");
        }
        installStartupScript(view,id,title);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if(request.isForMainFrame()) pageFailure="Spotify player page failed to load (WebView error " + error.getErrorCode() + ")";
            }
            @Override public void onReceivedHttpError(WebView v, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if(request.isForMainFrame()) pageFailure="Spotify player page returned HTTP " + response.getStatusCode();
            }
            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel(); pageFailure="Spotify player TLS connection failed";
            }
            @Override public void onPageFinished(WebView v, String url) {
                if(browser==v && requested) v.evaluateJavascript(SpotifyPlayback.rendererScript(track),null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                return request.isForMainFrame() && !("https".equals(request.getUrl().getScheme()) && "open.spotify.com".equals(request.getUrl().getHost()));
            }
        });
        view.loadUrl(SpotifyPlayback.url(id));
        poll(view,id,loadGeneration);
    }
    private static void installStartupScript(WebView view,String id,String title) {
        if(startupScript!=null) startupScript.remove();
        startupScript=WebViewCompat.addDocumentStartJavaScript(view,HOOK
            + "window.__streamxWantedTrack="+JSONObject.quote(id)+";window.__streamxWantedTitle="+JSONObject.quote(title==null?"":title)
            + ";window.__streamxAwaitingSelection=true;"+SpotifyPlayback.continuePromptScript(true),Collections.singleton("https://open.spotify.com"));
    }
    private static boolean hasMedia() {
        try {return new JSONObject(snapshot).optBoolean("media_present");}catch(Exception ignored){return false;}
    }
    private static void poll(WebView view, String id, long loadGeneration) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if(browser!=view || generation!=loadGeneration) return;
                // A retained SPA can get stuck switching songs even after accepting Play.
                // Reload the requested track once, without changing its browser/device profile.
                // Never interrupt a track that has already started or a user-paused load.
                if(requested && !audioStarted && !startupRecovered && pageFailure==null && pageComplete()
                        && System.currentTimeMillis()-started-adWaitMs>(warmStart?10000:30000) && (warmStart || !hasMedia())) {
                    startupRecovered=true; recoveredAt=System.currentTimeMillis();
                    control=warmStart?"track switch recovery":"desktop browser recovery";
                    recoveryReason=control;
                    if(!warmStart) SpotifyBrowser.configureDesktopRenderer(view);
                    view.loadUrl(SpotifyPlayback.url(id));
                }
                if(requested && !audioStarted && System.currentTimeMillis()-started-adWaitMs>60000) {
                    try {
                        JSONObject last=new JSONObject(snapshot);
                        if(!audioStarted) {
                            String reason="Spotify player did not create audio; Play control: " + control;
                            snapshot=last.put("error",reason).toString();
                            rendererContext.getSharedPreferences("spotify_session",0).edit().putString("playback_trace",reason).apply();
                        }
                    } catch(Exception ignored) {}
                }
                if(pendingSeek>=0) {
                    long position=pendingSeek;
                    view.evaluateJavascript("(() => { const m=window.__streamxMedia; if(!m || !Number.isFinite(m.duration) || m.readyState<1) return false; window.__streamxEnded=false; m.currentTime=Math.min(m.duration," + position + "/1000); return true; })()", raw -> {
                        if(browser==view && generation==loadGeneration && pendingSeek==position && "true".equals(raw)) pendingSeek=-1;
                    });
                }
                view.evaluateJavascript(SpotifyPlayback.continuePromptScript(true),null);
                if(requested) view.evaluateJavascript(SpotifyPlayback.rendererScript(id), raw -> {
                    if(browser==view && generation==loadGeneration && raw!=null) for(String known : new String[]{"ended","waiting for local device","audio running","resuming audio","waiting for audio","wrong page","waiting","waiting for track page","already playing","clicked"})
                        if(raw.equals(JSONObject.quote(known))) { control=known; break; }
                });
                view.evaluateJavascript("(() => { const actual=window.__streamxCheckTrack?.(); const mismatch=actual!==window.__streamxWantedTrack; const playback=window.__streamxReadPlayback?.();const restricted=mismatch && !window.__streamxIsAdvertisement?.() && playback?.context?.uri==='spotify:track:'+window.__streamxWantedTrack && playback?.restrictions?.disallow_toggling_shuffle_reasons?.includes('mft_disallow'); const m=window.__streamxMedia || document.querySelector('audio'); if(m) window.__streamxMedia=m; const bottomBtn=document.querySelector('button[data-testid=\"control-button-playpause\"]'); const bottomPaused=bottomBtn ? /^play\\b/i.test(bottomBtn.getAttribute('aria-label')||'') : false; const bottomPlaying=bottomBtn ? /^pause\\b/i.test(bottomBtn.getAttribute('aria-label')||'') : false; const pageReady=document.readyState; const blocked=(!m && document.body && /Playback disabled|browser doesn.t support Spotify/i.test(document.body.textContent.slice(0,12000))); if(!m && !bottomPlaying) return {selection_control:window.__streamxSelectionControl||null,advertisement:window.__streamxIsAdvertisement?.()||false,selection_restricted:!!restricted,local_activations:window.__streamxLocalActivations||0,continue_prompts:window.__streamxContinueCount||0,observed_title:window.__streamxObservedTitle?.()||null,observed_track_id:actual||null,state:'buffering',position_ms:0,duration_ms:0,page_ready:pageReady,media_present:false,error:blocked?'Spotify rejected the background web player on this device':null}; const isEnded=(m?.ended||window.__streamxEnded); const isPlaying=!isEnded && !window.__streamxPaused && !mismatch && !window.__streamxAwaitingSelection && m?.tagName==='AUDIO' && !m.paused && !m.muted && (m.currentTime>0 || m.readyState>=2); const isPaused=window.__streamxPaused || (m ? m.paused : bottomPaused); const state=isEnded ? 'ended' : isPlaying ? 'playing' : isPaused ? 'paused' : 'buffering'; const posMs=m ? (Math.floor(m.currentTime*1000)||0) : 0; const durMs=m && Number.isFinite(m.duration) ? (Math.floor(m.duration*1000)||0) : 0; const bufMs=m && m.buffered.length ? Math.floor(m.buffered.end(m.buffered.length-1)*1000) : posMs; return {selection_control:window.__streamxSelectionControl||null,advertisement:window.__streamxIsAdvertisement?.()||false,selection_restricted:!!restricted,local_activations:window.__streamxLocalActivations||0,continue_prompts:window.__streamxContinueCount||0,buffered_ms:bufMs,mime_type:window.__streamxAudioType||null,page_ready:pageReady,media_present:!!m,media_kind:m?.tagName||null,ready_state:m?.readyState||-1,network_state:m?.networkState||-1,media_error:m?.error?.code||0,paused:m?m.paused:bottomPaused,source_set:m?!!m.currentSrc:false,observed_title:window.__streamxObservedTitle?.()||null,observed_track_id:actual||null,state:state,position_ms:posMs,duration_ms:durMs,startup_error:window.__streamxMediaError||null,error:null}; })()", raw -> {
                    if(browser!=view || generation!=loadGeneration) return;
                    try {
                        Object parsed=new JSONTokener(raw==null?"null":raw).nextValue();
                        if(parsed instanceof JSONObject) {
                            JSONObject status=(JSONObject)parsed;
                            long adNow=System.currentTimeMillis();
                            if(lastAdSample>0) adWaitMs=Math.min(120000,adWaitMs+Math.max(0,adNow-lastAdSample));
                            lastAdSample=status.optBoolean("advertisement")?adNow:0;
                            if(!audioStarted && pageFailure==null && "Spotify rejected the background web player on this device".equals(status.optString("error"))) {
                                if(!startupRecovered) {
                                    startupRecovered=true;recoveredAt=System.currentTimeMillis();control="desktop browser recovery";recoveryReason=control;
                                    SpotifyBrowser.configureDesktopRenderer(view);view.loadUrl(SpotifyPlayback.url(id));
                                }
                                if(System.currentTimeMillis()-recoveredAt<5000)status.put("error",JSONObject.NULL);
                            }
                            // A stale remote-device item is not proof that local selection failed.
                            // Wait for local audio and a stable mismatch before failing.
                            status.put("track_id",id);
                            if(selectionGate.failed(status,requested,android.os.SystemClock.elapsedRealtime())) {
                                status.put("error","Spotify played a different track from the one selected. Please retry the track.");
                                view.evaluateJavascript(SpotifyPlayback.transportScript(false),null);
                            }
                            if(!requested) status.put("state","paused");
                            status.put("requested_play",requested);
                            status.put("browser_mode",view.getSettings().getUserAgentString().contains("X11; Linux")?"desktop":"mobile");
                            if("playing".equals(status.optString("state")) && status.optLong("position_ms")>0) audioStarted=true;
                            if(pageFailure!=null) status.put("error",pageFailure);
                            if(requested && !audioStarted && System.currentTimeMillis()-started-adWaitMs>60000) status.put("error","Spotify player did not create audio. Page: " + status.optString("page_ready","unknown") + "; Play control: " + control + "; Media ready: " + status.optInt("ready_state",-1)
                                + "; Network: " + status.optInt("network_state",-1) + "; Media error: " + status.optInt("media_error")
                                + "; Startup: " + (status.isNull("startup_error") ? "none" : status.optString("startup_error")));
                            status.put("play_control",control);
                            status.put("startup_ms",System.currentTimeMillis()-started);
                            status.put("recovered",startupRecovered);
                            status.put("recovery_reason",recoveryReason);
                            status.put("control_latency_ms",controlLatency);
                            status.put("control_sequence",controlSequence);
                            status.put("control_name",controlName);
                            String trace="Page: " + status.optString("page_ready","unknown") + "; Play: " + control + "; Audio element: " + status.optBoolean("media_present") + "; State: " + status.optString("state","unknown")
                                + "; Wanted: " + id + "; Observed: " + status.optString("observed_track_id","none") + "; Title: " + status.optString("observed_title","none")
                                + "; Ready: " + status.optInt("ready_state",-1) + "; Network: " + status.optInt("network_state",-1)
                                + "; Media error: " + status.optInt("media_error") + "; Recovery: " + startupRecovered
                                + "; Device prompt continued: " + status.optInt("continue_prompts") + "; Local activations: " + status.optInt("local_activations") + "; Browser: " + status.optString("browser_mode") + "; Selection: " + status.optString("selection_control","none");
                            if(!status.isNull("startup_error")) trace += "; Startup: " + status.optString("startup_error");
                            if(!status.isNull("error") && status.has("error")) trace += "; Error: " + status.optString("error");
                            if(!trace.equals(lastTrace)) {
                                lastTrace=trace; rendererContext.getSharedPreferences("spotify_session",0).edit().putString("playback_trace",trace).apply();
                            }
                            snapshot=status.toString();
                            if(!requested) view.evaluateJavascript(SpotifyPlayback.transportScript(false),null);
                        }
                    } catch(Exception ignored) { }
                    MAIN.postDelayed(this,audioStarted || !requested ? 500 : 100);
                });
            }
        },100);
    }
    private static boolean pageComplete() {
        try { return "complete".equals(new JSONObject(snapshot).optString("page_ready")); }
        catch(Exception ignored) { return false; }
    }
    static String diagnostics(Context context) {
        return "Background player: " + context.getSharedPreferences("spotify_session",0).getString("playback_trace", "No load command received yet. Check that the main app was rebuilt.");
    }
    static void pauseForNoise() { MAIN.post(() -> {
        requested=false;
        if(browser!=null) browser.evaluateJavascript(SpotifyPlayback.transportScript(false),null);
    }); }
    private static void destroy() {
        if(owner!=null && death!=null) { try { owner.unlinkToDeath(death,0); } catch(Exception ignored) {} }
        generation++; owner=null; death=null; track=null; requested=false;
        if(startupScript!=null){startupScript.remove();startupScript=null;}
        WebView old=browser; browser=null;
        if(old!=null) { old.stopLoading(); old.destroy(); }
        snapshot="{\"state\":\"idle\",\"position_ms\":0,\"duration_ms\":0}";
        rendererContext=null;
    }
}
