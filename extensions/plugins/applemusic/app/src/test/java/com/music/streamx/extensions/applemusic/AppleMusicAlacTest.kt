package com.music.streamx.extensions.applemusic
import org.junit.Test
import org.junit.Assert.*
class AppleMusicAlacTest {
 @Test fun endpointsAllowTlsAndLoopbackOnly() {
  for(url in listOf("https://music.example.com","https://music.example.com/alac","http://127.0.0.1:12440","http://localhost:12440"))AppleMusicAlac.validateEndpoint(url)
  for(url in listOf("http://music.example.com","https://user:pass@example.com","https://example.com?token=secret","file:///tmp/music","https://example.com#fragment","")) {
   assertThrows(IllegalArgumentException::class.java){AppleMusicAlac.validateEndpoint(url)}
  }
 }
}
