use std::{collections::HashMap, sync::{Arc, LazyLock, Mutex, atomic::{AtomicBool, AtomicI64, Ordering}}, time::Duration};
use jni::{JNIEnv, JavaVM, objects::{GlobalRef, JClass, JObject, JString, JValue}, sys::{jint, jlong, jstring}};
use librespot_core::{Session, config::SessionConfig, authentication::Credentials, SpotifyUri};
use librespot_playback::{player::{Player, PlayerEvent}, config::{PlayerConfig, Bitrate}, mixer::NoOpVolume,
    audio_backend::{Sink, SinkResult, SinkError}, convert::Converter, decoder::AudioPacket};
use librespot_metadata::audio::{AudioItem, AudioFileFormat};
use serde_json::{Value, json};
use tokio::sync::mpsc;

type State = Arc<Mutex<Value>>;
struct Engine { tx: mpsc::UnboundedSender<Command>, state: State, paused: Arc<AtomicBool> }
enum Command { Load(String, bool), Play, Pause, Seek(u32), Stop }
static ENGINES: LazyLock<Mutex<HashMap<i64, Engine>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicI64 = AtomicI64::new(1);
fn with_state(state: &State, f: impl FnOnce(&mut Value)) { f(&mut state.lock().unwrap_or_else(|e|e.into_inner())); }
fn fail(state: &State, text: &str) { with_state(state, |s| {s["state"]=json!("error");s["error"]=json!(text);}); }
fn valid_id(id: &str)->bool { id.len()==22 && id.bytes().all(|b|b.is_ascii_alphanumeric()) }
fn configured_bitrate(rate:i32)->Bitrate { match rate {96=>Bitrate::Bitrate96,160=>Bitrate::Bitrate160,_=>Bitrate::Bitrate320} }
fn source_format(item:&AudioItem, rate:i32)->Option<(&'static str,i32)> {
    use AudioFileFormat::*;
    let order=match rate {
        96=>[OGG_VORBIS_96,MP3_96,OGG_VORBIS_160,MP3_160,MP3_256,OGG_VORBIS_320,MP3_320],
        160=>[OGG_VORBIS_160,MP3_160,OGG_VORBIS_96,MP3_96,MP3_256,OGG_VORBIS_320,MP3_320],
        _=>[OGG_VORBIS_320,MP3_320,MP3_256,OGG_VORBIS_160,MP3_160,OGG_VORBIS_96,MP3_96]};
    order.into_iter().find(|f|item.files.get(f).is_some()).map(|f|match f {
        OGG_VORBIS_96=>("audio/vorbis",96),OGG_VORBIS_160=>("audio/vorbis",160),OGG_VORBIS_320=>("audio/vorbis",320),
        MP3_96=>("audio/mpeg",96),MP3_160=>("audio/mpeg",160),MP3_256=>("audio/mpeg",256),_=>("audio/mpeg",320)})
}
struct AndroidSink { vm:Arc<JavaVM>, object:GlobalRef, paused:Arc<AtomicBool> }
impl AndroidSink {
    fn control(&self,name:&str)->SinkResult<()> {
        let mut env=self.vm.attach_current_thread().map_err(|_|SinkError::StateChange("JNI thread unavailable".into()))?;
        if env.call_method(self.object.as_obj(),name,"()V",&[]).is_err() {
            let _=env.exception_clear();
            return Err(SinkError::StateChange("Android audio state failed".into()));
        }
        Ok(())
    }
}
impl Sink for AndroidSink {
    fn start(&mut self)->SinkResult<()> {self.control("start")}
    fn stop(&mut self)->SinkResult<()> {self.control("pause")}
    fn write(&mut self,packet:AudioPacket,_:&mut Converter)->SinkResult<()> {
        if self.paused.load(Ordering::Relaxed) { return Ok(()); }
        let samples=match packet {AudioPacket::Samples(v)=>v,AudioPacket::Raw(_)=>return Err(SinkError::OnWrite("Unsupported encoded packet".into()))};
        let pcm:Vec<i16>=samples.into_iter().map(|x|(x.clamp(-1.0,1.0)*32767.0).round() as i16).collect();
        let mut env=self.vm.attach_current_thread().map_err(|_|SinkError::OnWrite("JNI thread unavailable".into()))?;
        let array=env.new_short_array(pcm.len() as i32).map_err(|_|SinkError::OnWrite("PCM allocation failed".into()))?;
        env.set_short_array_region(&array,0,&pcm).map_err(|_|SinkError::OnWrite("PCM copy failed".into()))?;
        let object=JObject::from(array);
        let written=match env.call_method(self.object.as_obj(),"write","([S)I",&[JValue::Object(&object)]).and_then(|v|v.i()) {
            Ok(value)=>value,
            Err(_)=>{let _=env.exception_clear();return Err(SinkError::OnWrite("Android audio output failed".into()));}
        };
        // Attached decoder threads live for the session; free each packet JNI reference.
        let _=env.delete_local_ref(object);
        if written<0 {return Err(SinkError::OnWrite("Android audio output unavailable".into()));}
        Ok(())
    }
}
async fn run(token:String,client:String,lifetime:u64,user:String,cache:String,rate:i32,vm:Arc<JavaVM>,output:GlobalRef,state:State,
    paused:Arc<AtomicBool>,mut commands:mpsc::UnboundedReceiver<Command>) {
    let mut config=SessionConfig::default(); config.tmp_dir=cache.into(); config.autoplay=Some(false);
    let session=Session::new(config,None);
    if session.spclient().set_browser_credentials(token.clone(),client,lifetime).is_err() {
        fail(&state,"Native browser authorization is expired or incomplete");return;
    }
    match tokio::time::timeout(Duration::from_secs(20),session.connect(Credentials::with_access_token(token),false)).await {
        Ok(Ok(()))=>{},_=>{fail(&state,"Native Spotify authentication failed; use web playback");session.shutdown();return;}
    }
    if session.username()!=user {fail(&state,"Native session does not match the signed-in Spotify account");session.shutdown();return;}
    for _ in 0..60 {if session.get_user_attribute("type").is_some(){break;}tokio::time::sleep(Duration::from_millis(50)).await;}
    if session.get_user_attribute("type").as_deref()!=Some("premium") {
        fail(&state,"Native playback requires a verified Spotify Premium account");session.shutdown();return;
    }
    let config=PlayerConfig {bitrate:configured_bitrate(rate),gapless:false,normalisation:false,
        position_update_interval:Some(Duration::from_millis(250)),..Default::default()};
    let sink_paused=paused.clone();
    let player=Player::new(config,session.clone(),Box::new(NoOpVolume),move||Box::new(AndroidSink{vm,object:output,paused:sink_paused}));
    let mut events=player.get_player_event_channel(); let mut target=String::new();
    loop {tokio::select! {
        command=commands.recv()=>match command {
            Some(Command::Load(id,play))=>{
                target=format!("spotify:track:{id}");
                with_state(&state,|s|{s["track_id"]=json!(id);s["state"]=json!("buffering");s["position_ms"]=json!(0);s["duration_ms"]=json!(0);s["sample_rate_hz"]=Value::Null;s["channels"]=Value::Null;s["bit_depth"]=Value::Null;s["error"]=Value::Null;s["mime_type"]=Value::Null;s["bitrate_kbps"]=Value::Null;});
                if let Ok(uri)=SpotifyUri::from_uri(&target){paused.store(!play,Ordering::Relaxed);player.load(uri,play,0);}
            },
            Some(Command::Play)=>{paused.store(false,Ordering::Relaxed);player.play();},
            Some(Command::Pause)=>{paused.store(true,Ordering::Relaxed);player.pause();},
            Some(Command::Seek(position))=>player.seek(position),
            Some(Command::Stop)|None=>break,
        },
        event=events.recv()=>match event {
            Some(PlayerEvent::Playing{track_id,position_ms,..}) if track_id.to_uri()==target=>with_state(&state,|s|{s["state"]=json!("playing");s["position_ms"]=json!(position_ms);}),
            Some(PlayerEvent::Paused{track_id,position_ms,..}) if track_id.to_uri()==target=>with_state(&state,|s|{s["state"]=json!("paused");s["position_ms"]=json!(position_ms);}),
            Some(PlayerEvent::Loading{track_id,position_ms,..}) if track_id.to_uri()==target=>with_state(&state,|s|{s["state"]=json!("buffering");s["position_ms"]=json!(position_ms);}),
            Some(PlayerEvent::PositionChanged{track_id,position_ms,..}|PlayerEvent::PositionCorrection{track_id,position_ms,..}|PlayerEvent::Seeked{track_id,position_ms,..}) if track_id.to_uri()==target=>with_state(&state,|s|s["position_ms"]=json!(position_ms)),
            Some(PlayerEvent::TrackChanged{audio_item})=>{
                if audio_item.uri==target {with_state(&state,|s|{
                    s["duration_ms"]=json!(audio_item.duration_ms);s["sample_rate_hz"]=json!(44100);s["channels"]=json!(2);s["bit_depth"]=json!(16);
                    if let Some((mime,bitrate))=source_format(&audio_item,rate){s["mime_type"]=json!(mime);s["bitrate_kbps"]=json!(bitrate);}
                });}
            },
            Some(PlayerEvent::EndOfTrack{track_id,..}) if track_id.to_uri()==target=>with_state(&state,|s|s["state"]=json!("ended")),
            Some(PlayerEvent::Unavailable{track_id,reason,..}) if track_id.to_uri()==target=>{
                let message=match reason {
                    "audio_key"=>"Premium verified, but Spotify did not supply a usable native audio key",
                    "metadata_authentication"=>"Premium verified; Spotify rejected or expired native HTTP authorization",
                    "metadata_permission"=>"Premium verified; Spotify denied native metadata access",
                    "availability"=>"Premium verified; Spotify reports this track unavailable for native playback",
                    "metadata"=>"Premium verified; native track metadata or availability failed",
                    "download"=>"Premium verified; native audio download failed",
                    "unsupported_format"=>"Premium verified; no supported native audio format was available",
                    "decoder"=>"Premium verified; the native decoder could not read the audio",
                    "seek"=>"Premium verified; native initial seek failed",
                    _=>"Premium verified; native track loading failed",
                };
                fail(&state,message);with_state(&state,|s|s["failure_stage"]=json!(reason));break;
            },
            None=>{fail(&state,"Native decoder stopped unexpectedly; web playback is available");break;},_=>{},
        }
    }}
    paused.store(true,Ordering::Relaxed); player.stop(); session.shutdown();
}
#[no_mangle]
pub extern "system" fn Java_com_music_streamx_extensions_spotify_SpotifyNativeEngine_create(
    mut env:JNIEnv,_:JClass,token:JString,client:JString,lifetime:jlong,user:JString,cache:JString,rate:jint,sink:JObject)->jlong {
    let result=(||->Result<i64,()> {
        let token:String=env.get_string(&token).map_err(|_|())?.into();
        let client:String=env.get_string(&client).map_err(|_|())?.into();
        if lifetime<60 {return Err(());}
        let lifetime=lifetime as u64;
        let user:String=env.get_string(&user).map_err(|_|())?.into();
        let cache:String=env.get_string(&cache).map_err(|_|())?.into();
        if token.is_empty()||user.is_empty()||![96,160,320].contains(&rate){return Err(());}
        let vm=Arc::new(env.get_java_vm().map_err(|_|())?);let output=env.new_global_ref(sink).map_err(|_|())?;
        let state=Arc::new(Mutex::new(json!({"backend":"native","state":"buffering","position_ms":0,"duration_ms":0,"error":null})));
        let paused=Arc::new(AtomicBool::new(true));let(tx,rx)=mpsc::unbounded_channel();let handle=NEXT.fetch_add(1,Ordering::Relaxed);
        ENGINES.lock().unwrap_or_else(|e|e.into_inner()).insert(handle,Engine{tx,state:state.clone(),paused:paused.clone()});
        std::thread::spawn(move||{
            match tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build() {
                Ok(runtime)=>runtime.block_on(run(token,client,lifetime,user,cache,rate,vm,output,state,paused,rx)),
                Err(_)=>fail(&state,"Native runtime could not start"),
            }
        }); Ok(handle)
    })();
    match result {Ok(handle)=>handle,Err(_)=>{let _=env.throw_new("java/lang/IllegalStateException","Native Spotify engine could not initialize");0}}
}
#[no_mangle]
pub extern "system" fn Java_com_music_streamx_extensions_spotify_SpotifyNativeEngine_command(mut env:JNIEnv,_:JClass,handle:jlong,command:jint,id:JString,position:jlong,play:jint) {
    let id:String=env.get_string(&id).map(|s|s.into()).unwrap_or_default();
    let engines=ENGINES.lock().unwrap_or_else(|e|e.into_inner());if let Some(engine)=engines.get(&handle) {
        let command=match command {0 if valid_id(&id)=>Command::Load(id,play!=0),1=>Command::Play,2=>{engine.paused.store(true,Ordering::Relaxed);Command::Pause},3=>Command::Seek(position.clamp(0,u32::MAX as i64) as u32),_=>return};
        let _=engine.tx.send(command);
    }
}
#[no_mangle]
pub extern "system" fn Java_com_music_streamx_extensions_spotify_SpotifyNativeEngine_status(env:JNIEnv,_:JClass,handle:jlong)->jstring {
    let engines=ENGINES.lock().unwrap_or_else(|e|e.into_inner());
    let value=engines.get(&handle).map(|engine|engine.state.lock().unwrap_or_else(|e|e.into_inner()).to_string())
        .unwrap_or_else(||json!({"state":"idle","error":"Native engine was stopped"}).to_string());
    env.new_string(value).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_music_streamx_extensions_spotify_SpotifyNativeEngine_close(_:JNIEnv,_:JClass,handle:jlong) {
    if let Some(engine)=ENGINES.lock().unwrap_or_else(|e|e.into_inner()).remove(&handle) {engine.paused.store(true,Ordering::Relaxed);let _=engine.tx.send(Command::Stop);}
}
#[cfg(test)] mod tests {
    use super::*;
    #[tokio::test] async fn browser_authorization_requires_a_complete_unexpired_pair(){
        let session=Session::new(SessionConfig::default(),None);
        assert!(session.spclient().set_browser_credentials("".into(),"client".into(),120).is_err());
        assert!(session.spclient().set_browser_credentials("bearer".into(),"".into(),120).is_err());
        assert!(session.spclient().set_browser_credentials("bearer".into(),"client".into(),30).is_err());
        assert!(session.spclient().set_browser_credentials("bearer".into(),"client".into(),120).is_ok());
        session.shutdown();
    }
    #[test] fn accepts_only_catalogue_track_ids(){assert!(valid_id("0VjIjW4GlUZAMYd2vXMi3b"));for id in ["","../track","spotify:track:test"]{assert!(!valid_id(id));}}
    #[test] fn quality_is_a_stream_choice_not_a_lossless_claim(){assert_eq!(configured_bitrate(96),Bitrate::Bitrate96);assert_eq!(configured_bitrate(160),Bitrate::Bitrate160);assert_eq!(configured_bitrate(320),Bitrate::Bitrate320);}
}


// An independent transfer session: it never creates an audio sink or touches
// the live player's queue. The signed provider owns the output pipe.
#[no_mangle]
pub extern "system" fn Java_com_music_streamx_extensions_spotify_SpotifyNativeEngine_download(
    mut env: JNIEnv, _: JClass, token: JString, client: JString, lifetime: jlong,
    user: JString, cache: JString, track: JString, rate: jint, output: jint,
) {
    let result = (|| -> Result<(), String> {
        let token: String = env.get_string(&token).map_err(|_| "Invalid authorization")?.into();
        let client: String = env.get_string(&client).map_err(|_| "Invalid authorization")?.into();
        let user: String = env.get_string(&user).map_err(|_| "Invalid account")?.into();
        let cache: String = env.get_string(&cache).map_err(|_| "Invalid cache")?.into();
        let track: String = env.get_string(&track).map_err(|_| "Invalid track")?.into();
        if !valid_id(&track) || ![96,160,320].contains(&rate) || lifetime < 60 { return Err("Invalid native download request".into()); }
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().map_err(|_| "Native runtime unavailable")?;
        runtime.block_on(async {
            use std::io::{Read, Seek, SeekFrom, Write};
            use std::os::fd::FromRawFd;
            use librespot_audio::{AudioFile, AudioDecrypt};
            let mut config = SessionConfig::default(); config.tmp_dir = cache.into(); config.autoplay = Some(false);
            let session = Session::new(config, None);
            let transfer = async {
                session.spclient().set_browser_credentials(token.clone(), client, lifetime as u64).map_err(|_| "Authorization expired")?;
                tokio::time::timeout(Duration::from_secs(20), session.connect(Credentials::with_access_token(token), false)).await
                    .map_err(|_| "Native authorization timed out")?.map_err(|_| "Native authorization failed")?;
                if session.username() != user { return Err("Native account changed".into()); }
                for _ in 0..60 { if session.get_user_attribute("type").is_some() { break; } tokio::time::sleep(Duration::from_millis(50)).await; }
                if session.get_user_attribute("type").as_deref() != Some("premium") { return Err("Native downloads require Spotify Premium".into()); }
                let uri = SpotifyUri::from_uri(&format!("spotify:track:{track}")).map_err(|_| "Invalid track")?;
                let item = AudioItem::get_file(&session, uri.clone()).await.map_err(|_| "Track is unavailable")?;
                let format = match rate { 96 => AudioFileFormat::OGG_VORBIS_96, 160 => AudioFileFormat::OGG_VORBIS_160, _ => AudioFileFormat::OGG_VORBIS_320 };
                let file_id = *item.files.get(&format).ok_or("Requested native rendition is unavailable")?;
                let file = AudioFile::open(&session, file_id, (rate as usize * 1000) / 8).await.map_err(|_| "Native transfer failed")?;
                let key = session.audio_key().request(match uri { SpotifyUri::Track { id } => id, _ => return Err("Invalid track".into()) }, file_id).await.map_err(|_| "Native audio is unavailable")?;
                let mut audio = AudioDecrypt::new(Some(key), file);
                // The player's own Vorbis reader starts after Spotify's normalization packet.
                audio.seek(SeekFrom::Start(0xa7)).map_err(|_| "Invalid audio header")?;
                let mut magic = [0u8; 4]; audio.read_exact(&mut magic).map_err(|_| "Incomplete audio header")?;
                if &magic != b"OggS" { return Err("Unsupported native audio container".into()); }
                let fd = unsafe { libc::dup(output) }; if fd < 0 { return Err("Download pipe closed".into()); }
                let mut sink = unsafe { std::fs::File::from_raw_fd(fd) };
                sink.write_all(&magic).map_err(|_| "Download cancelled")?;
                let mut buffer = [0u8; 65536]; let mut total = 4usize;
                loop {
                    let count = audio.read(&mut buffer).map_err(|_| "Native transfer interrupted")?;
                    if count == 0 { break; }
                    total += count; if total > 512 * 1024 * 1024 { return Err("Native file exceeds size limit".into()); }
                    sink.write_all(&buffer[..count]).map_err(|_| "Download cancelled")?;
                }
                sink.flush().map_err(|_| "Could not finish native download")?;
                Ok(())
            }.await;
            session.shutdown(); transfer
        })
    })();
    if let Err(message) = result { let _ = env.throw_new("java/io/IOException", message); }
}
