package com.music.streamx.extensions.applemusic

import org.junit.Test
import org.junit.Assert.*

class AppleMusicTermuxTest {
 @Test fun everyTaskIsValidBashAndRemainsBounded() {
  for(task in AppleMusicTermuxScripts.Task.entries) {
   val source=AppleMusicTermuxScripts.script(task,"content://installer/bridge.gz","content://installer/tasks/test","fixture-token-12345678901234567890")
   val process=ProcessBuilder("bash","-n").start()
   process.outputStream.use {it.write(source.toByteArray())}
   val errors=process.errorStream.bufferedReader().readText()
   assertEquals("${task.name}: $errors",0,process.waitFor())
   assertTrue(source.length<32000)
  }
 }
 @Test fun embeddedPythonParsesAndMusicLinksAreRestricted() {
  for(task in AppleMusicTermuxScripts.Task.entries) {
   val source=AppleMusicTermuxScripts.script(task,"http://127.0.0.1/asset","http://127.0.0.1/progress","fixture")
   for(match in Regex("python - <<'PYCODE'\\n(.*?)\\nPYCODE",RegexOption.DOT_MATCHES_ALL).findAll(source)) {
    val p=ProcessBuilder("python3","-c","import sys; compile(sys.stdin.read(), '<installer>', 'exec')").start()
    p.outputStream.use {it.write(match.groupValues[1].toByteArray())}
    val error=p.errorStream.bufferedReader().readText();assertEquals(error,0,p.waitFor())
   }
  }
  assertEquals("https://music.apple.com/us/song/123",AppleMusicTermuxScripts.validateMusicUrl("https://music.apple.com/us/song/123"))
  for(url in listOf("http://music.apple.com/us/song/123","https://music.apple.com.evil.test/123","https://user:pass@music.apple.com/123"))assertThrows(IllegalArgumentException::class.java){AppleMusicTermuxScripts.validateMusicUrl(url)}
 }
 @Test fun quotingPreservesShellMetacharacters() {
  val value="a'\n\$(printf unsafe); & b"
  val process=ProcessBuilder("bash","-c","printf %s "+AppleMusicTermuxScripts.quote(value)).start()
  assertEquals(value,process.inputStream.bufferedReader().readText())
  assertEquals(0,process.waitFor())
 }
 @Test fun privateInteractiveTasksDoNotCaptureTranscript() {
  assertFalse(AppleMusicTermuxScripts.Task.LOGIN.capture)
  assertFalse(AppleMusicTermuxScripts.Task.START_WRAPPER.capture)
  assertTrue(AppleMusicTermuxScripts.Task.LOGIN.interactive)
 }
 @Test fun terminalOutputRedactsCredentialsAndEscapes() {
  val value="\u001b[31mBearer secret-token STREAMX_ALAC_TOKEN=other-secret eyJabc.def.ghi"
  val cleaned=AppleMusicTermuxLog.sanitize(value)
  assertFalse(cleaned.contains("secret-token"));assertFalse(cleaned.contains("other-secret"));assertFalse(cleaned.contains("eyJabc"));assertFalse(cleaned.contains("\u001b"))
 }
 @Test fun installerDoesNotOverwriteExistingDownloaderConfig() {
  val source=AppleMusicTermuxScripts.script(AppleMusicTermuxScripts.Task.INSTALL,"content://installer/bridge.gz","content://installer/tasks/test","token")
  assertTrue(source.contains("if [ ! -e config.yaml ]"))
  assertTrue(source.contains("amdl_android_arm64"))
 }
 @Test fun localQemuVerifiesPinnedArtifactBeforeExtractingAndUsesPrivateLogin() {
  val source=AppleMusicTermuxScripts.script(AppleMusicTermuxScripts.Task.LOCAL_QEMU,"unused","unused","fixture")
  assertTrue(source.contains(AppleMusicTermuxScripts.QEMU_ARTIFACT))
  assertTrue(source.contains(AppleMusicTermuxScripts.QEMU_SHA256))
  assertTrue(source.indexOf("sha256sum --check")<source.indexOf("archive.extractall"))
  assertTrue(source.contains("read -rs SX_SECRET"))
  assertTrue(source.contains("--host 127.0.0.1 --host-port 12340"))
  assertTrue(source.contains("QEMU_BIN="))
  assertFalse(AppleMusicTermuxScripts.Task.LOCAL_QEMU.capture)
  assertTrue(AppleMusicTermuxScripts.Task.LOCAL_QEMU.interactive)
 }
}
