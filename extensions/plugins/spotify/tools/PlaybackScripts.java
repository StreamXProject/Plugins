package com.music.streamx.extensions.spotify;
public class PlaybackScripts {
 public static void main(String[] args) throws Exception {
  String id="1234567890123456789012";
  System.out.println(new org.json.JSONObject().put("pause",SpotifyPlayback.transportScript(false))
   .put("resume",SpotifyPlayback.transportScript(true)).put("renderer",SpotifyPlayback.rendererScript(id))
   .put("touch",SpotifyTouchPlayback.script()).put("activateLocal",SpotifyPlayback.activateLocalScript()).put("selectRow",SpotifyPlayback.selectRowScript(id)).put("continuePrompt",SpotifyPlayback.continuePromptScript()).put("localPrompt",SpotifyPlayback.continuePromptScript(true)).put("navigate",SpotifyPlayback.navigateScript(id,true,"New song")).toString());
 }
}
