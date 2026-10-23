package com.music.streamx.extensions.applemusic

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class AppleMusicQemuGuestTest {
    private fun fixture(names: List<String>,block: (File,File)->Unit) {
        val root=Files.createTempDirectory("qemu-guest-test").toFile()
        try {
            val zip=File(root,"guest.zip")
            ZipOutputStream(zip.outputStream()).use {out->for(name in names){out.putNextEntry(ZipEntry(name));out.write("fixture".toByteArray());out.closeEntry()}}
            val dest=File(root,"out").apply{mkdir()}
            block(zip,dest)
        } finally {root.deleteRecursively()}
    }
    @Test fun onlyGuestDataIsExtractedAndTraversalEntriesAreIgnored() {
        fixture(AppleMusicQemuGuest.names.map{"qemu/$it"}+listOf("../../escape","wrapper-lite-qemu","qemu/bin/qemu-system-x86_64")) {zip,out->
            AppleMusicQemuGuest.extract(zip,out)
            assertEquals(AppleMusicQemuGuest.names.toSet(),out.listFiles()!!.map{it.name}.toSet())
            assertFalse(File(out.parentFile,"escape").exists())
            assertTrue(out.listFiles()!!.all{it.readText()=="fixture"})
        }
    }
    @Test fun missingGuestFilesRejectInstallation() {
        fixture(listOf("qemu/data.img")) {zip,out->assertThrows(IllegalStateException::class.java){AppleMusicQemuGuest.extract(zip,out)}}
    }
    @Test fun androidLoginUsesTerminalEchoControlInsteadOfBashReadSilentFlag() {
        val script=AppleMusicQemuCommand.script("/apk/libwrapper_exec.so",true)
        assertTrue(script.indexOf("stty -echo")<script.indexOf("IFS= read -r SX_SECRET"))
        assertTrue(script.indexOf("stty \"${'$'}SX_TTY\"; printf")>script.indexOf("IFS= read -r SX_SECRET"))
        assertFalse(script.contains("read -rs"))
        assertTrue(script.contains("trap 'stty"))
        val process=ProcessBuilder("bash","-n").start()
        process.outputStream.use{it.write(script.toByteArray())}
        assertEquals(0,process.waitFor())
    }
}
