package com.music.streamx.extensions.applemusic

/** Fixed, reviewable tasks. No account password is embedded in an intent or retained log. */
internal object AppleMusicTermuxScripts {
    const val HOME="/data/data/com.termux/files/home"
    const val PREFIX="/data/data/com.termux/files/usr"
    const val ENABLE="mkdir -p ~/.termux; echo allow-external-apps=true >> ~/.termux/termux.properties; termux-reload-settings"
    const val TERMUX_URL="https://github.com/termux/termux-app/releases"
    const val WRAPPER_URL="https://github.com/WorldObservationLog/wrapper/actions/workflows/build-lite.yml?query=branch%3Alite"
    enum class Task(val title: String,val interactive: Boolean=false,val capture: Boolean=true) {
        INSTALL("Install audio tools",true), FFMPEG("Install FFmpeg",true), DOWNLOAD("Download Apple Music link",true), STORAGE("Allow Music folder",true),
        LOCAL_QEMU("Set up local QEMU wrapper",true,false),
        IMPORT("Install wrapper package",true), LOGIN("Sign in to local wrapper",true,false),
        START_WRAPPER("Start local wrapper",true,false), START_BRIDGE("Start local streaming"),
        STOP("Stop local streaming"), CHECK("Check local setup"), LOGS("Read streaming logs")
    }
    // Pinned upstream artifact. nightly.link transports the GitHub artifact; the
    // digest is verified against GitHub before extracting or executing anything.
    const val QEMU_ARTIFACT="https://nightly.link/WorldObservationLog/wrapper/actions/artifacts/10973424399.zip"
    const val QEMU_SHA256="12bf5f1bf20d694852f3427994a3acf990e80ecde788f05c941bc047c3de5e10"
    private fun localQemu(binaryUri: String,progressUri: String,token: String): String {
        val imported=script(Task.IMPORT,binaryUri,progressUri,token)
            .replace("curl --fail --silent --show-error --max-time 600 ${quote(binaryUri)} --output wrapper.zip.part",
                "curl --fail --location --silent --show-error --connect-timeout 30 --max-time 1200 ${quote(QEMU_ARTIFACT)} --output wrapper.zip.part\n"+
                "printf '%s  %s\\n' ${quote(QEMU_SHA256)} wrapper.zip.part | sha256sum --check --status || { echo 'Wrapper checksum mismatch; package not installed'; exit 1; }")
        val login=script(Task.LOGIN,binaryUri,progressUri,token)
        val start=script(Task.START_WRAPPER,binaryUri,progressUri,token)
        return buildString {
            append("""
                [ "${'$'}(uname -m)" = aarch64 ] || { echo 'Local QEMU requires Android arm64'; exit 1; }
                pkg install -y --no-install-recommends curl python unzip qemu-system-x86-64-headless
                if [ ! -f wrapper-launcher ]; then
                (
            """.trimIndent())
            append("\n");append(imported);append("\n)\nfi\n")
            append("""
                if curl --fail --silent --max-time 3 http://127.0.0.1:12340/status >/dev/null; then
                    echo 'Local wrapper is already running. Return to BitChord and check the connection.'
                    exit 0
                fi
                printf 'Sign in now? [y/N] (choose N for an existing signed-in disk): '
                IFS= read -r SX_SIGNIN
                case "${'$'}SX_SIGNIN" in y|Y)
                (
            """.trimIndent())
            append("\n");append(login)
            append("\n) || { echo 'Sign-in failed; service was not started'; exit 1; }\n;; esac\n")
            append("echo 'Starting QEMU. Return to BitChord and select Check and use connection when ready.'\n")
            append(start)
        }
    }

    fun validateMusicUrl(value: String): String {
        val clean=value.trim();val uri=runCatching {java.net.URI(clean)}.getOrNull()
        require(clean.length<=4096 && uri?.scheme=="https" && uri.host=="music.apple.com" && uri.rawUserInfo==null && !uri.path.isNullOrBlank()){"Enter an HTTPS Apple Music album, song or playlist link"}
        return clean
    }
    fun quote(value: String)="'"+value.replace("'","'\\''")+"'"
    fun script(task: Task,binaryUri: String,progressUri: String,token: String,musicUrl: String=""): String {
        val binary=quote(binaryUri);val progress=quote(progressUri)
        val preamble="""
            set -eu
            umask 077
            SX_DIR="${'$'}HOME/.local/share/bitchord-alac"
            mkdir -p "${'$'}SX_DIR"
            cd "${'$'}SX_DIR"
            sx_progress() { curl --fail --silent --max-time 2 --request POST --data-binary "${'$'}1" $progress >/dev/null 2>&1 || true; printf '\n%s\n' "${'$'}1"; }
        """.trimIndent()
        val body=when(task) {
            Task.LOCAL_QEMU -> localQemu(binaryUri,progressUri,token)
            Task.INSTALL -> """
                [ "${'$'}(uname -m)" = aarch64 ] || { echo 'Android arm64 is required'; exit 1; }
                sx_progress 'Updating Termux packages'
                pkg update -y
                pkg upgrade -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold
                pkg install -y --no-install-recommends -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold curl python gzip unzip
                sx_progress 'Installing the native streaming helper'
                curl --fail --silent --show-error --max-time 120 $binary --output streamx-alac.gz.part
                gzip -dc streamx-alac.gz.part > streamx-alac.part
                chmod 700 streamx-alac.part
                mv streamx-alac.part streamx-alac
                rm -f streamx-alac.gz.part
                sx_progress 'Downloading the Apple Music downloader'
                curl --fail --location --retry 2 --connect-timeout 20 --output amdl.part https://github.com/zhaarey/apple-music-downloader/releases/latest/download/amdl_android_arm64
                chmod 700 amdl.part
                mv amdl.part amdl
                if [ ! -e config.yaml ]; then
                    printf '%s\n' 'general:' '  lite-server: "http://127.0.0.1:12340"' 'paths:' '  alac: "downloads/alac"' '  atmos: "downloads/atmos"' '  aac: "downloads/aac"' '  mv: "downloads/mv"' > config.yaml
                fi
                mkdir -p downloads/alac downloads/aac downloads/atmos downloads/mv
                sx_progress 'Audio tools installed. Install and sign in to the local wrapper next.'
            """.trimIndent()
            Task.FFMPEG -> "pkg install -y ffmpeg"
            Task.DOWNLOAD -> "[ -x ./amdl ] || { echo 'Install audio tools first'; exit 1; }; ./amdl "+quote(if(musicUrl.isBlank())"https://music.apple.com/us/song/123" else validateMusicUrl(musicUrl))
            Task.STORAGE -> """
                termux-setup-storage
                for SX_WAIT in 1 2 3 4 5 6; do [ -w "${'$'}HOME/storage/shared" ] && break; sleep 5; done
                [ -w "${'$'}HOME/storage/shared" ] || { echo 'Storage access was not granted; existing configuration was preserved'; exit 1; }
                python - <<'PYCODE'
                import pathlib,re,shutil,json
                path=pathlib.Path('config.yaml')
                if not path.exists(): raise SystemExit('Install audio tools first')
                lines=path.read_text().splitlines(keepends=True)
                start=next((i for i,l in enumerate(lines) if l.startswith('paths:')),len(lines))
                end=next((i for i in range(start+1,len(lines)) if lines[i].strip() and not lines[i][0].isspace() and not lines[i].startswith('#')),len(lines))
                base=pathlib.Path.home()/'storage/shared/Music'
                folders={'alac':str(base/'amdl'),'atmos':str(base/'amdl-atmos'),'aac':str(base/'amdl-aac'),'mv':str(base/'amdl-mv')}
                block=lines[start:end] if start<len(lines) else ['paths:\n']
                seen=set()
                for i,line in enumerate(block):
                    key=line.strip().split(':',1)[0]
                    if line[0].isspace() and key in folders:
                        block[i]='  '+key+': '+json.dumps(folders[key])+'\n';seen.add(key)
                for key,value in folders.items():
                    pathlib.Path(value).mkdir(parents=True,exist_ok=True)
                    if key not in seen: block.append('  '+key+': '+json.dumps(value)+'\n')
                backup=pathlib.Path('config.yaml.pre-shared')
                if not backup.exists(): shutil.copy2(path,backup)
                path.write_text(''.join(lines[:start]+block+lines[end:]))
                PYCODE
                echo 'Shared Music download folders configured. Other settings were preserved.'
            """.trimIndent()
            Task.IMPORT -> """
                sx_progress 'Reading the wrapper package'
                trap 'rm -f wrapper.zip.part' EXIT
                curl --fail --silent --show-error --max-time 600 $binary --output wrapper.zip.part
                python - <<'PYCODE'
                import pathlib,zipfile,stat,os,tempfile,shutil
                archive=zipfile.ZipFile('wrapper.zip.part')
                final=pathlib.Path('wrapper-package').resolve()
                if final.exists(): raise SystemExit('Wrapper already installed; existing account/session files were preserved')
                root=pathlib.Path(tempfile.mkdtemp(prefix='wrapper-install-')).resolve()
                try:
                    infos=archive.infolist()
                    if sum(i.file_size for i in infos)>1536*1024*1024: raise SystemExit('Wrapper package exceeds 1.5 GB')
                    for i in infos:
                        p=(root/i.filename).resolve()
                        if not p.is_relative_to(root) or stat.S_ISLNK(i.external_attr>>16): raise SystemExit('Unsafe wrapper archive')
                    archive.extractall(root)
                    launchers=list(root.rglob('wrapper-lite-qemu'))
                    if len(launchers)!=1: raise SystemExit('Choose the wrapper-lite-qemu-android-aarch64 artifact ZIP')
                    launcher=launchers[0]
                    for name in ['qemu/vmlinuz-lite-qemu','qemu/lite-initramfs.cpio.gz','qemu/data.img']:
                        if not (launcher.parent/name).is_file(): raise SystemExit('Wrapper package missing '+name)
                    launcher.chmod(0o700)
                    relative=launcher.relative_to(root)
                    os.replace(root,final)
                    (pathlib.Path('wrapper-launcher')).write_text(str(final/relative))
                finally:
                    if root.exists(): shutil.rmtree(root)
                PYCODE
                pkg install -y --no-install-recommends qemu-system-x86-64-headless
                rm -f wrapper.zip.part
                sx_progress 'Local wrapper installed. Sign in before starting playback.'
            """.trimIndent()
            Task.LOGIN -> """
                [ -f wrapper-launcher ] || { echo 'Install the Android wrapper package first'; exit 1; }
                SX_WRAPPER="${'$'}(cat wrapper-launcher)"
                cd "${'$'}(dirname "${'$'}SX_WRAPPER")"
                printf 'Apple Account: '; IFS= read -r SX_ACCOUNT
                printf 'Password (hidden): '; IFS= read -rs SX_SECRET; printf '\n'
                trap 'unset SX_ACCOUNT SX_SECRET' EXIT
                QEMU_BIN="${'$'}PREFIX/bin/qemu-system-x86_64" "${'$'}SX_WRAPPER" --accel tcg --login "${'$'}SX_ACCOUNT:${'$'}SX_SECRET"
            """.trimIndent()
            Task.START_WRAPPER -> """
                [ -f wrapper-launcher ] || { echo 'Install the Android wrapper package first'; exit 1; }
                if curl --fail --silent --max-time 3 http://127.0.0.1:12340/status >/dev/null; then echo 'Local wrapper is already running'; exit 0; fi
                SX_WRAPPER="${'$'}(cat wrapper-launcher)"
                cd "${'$'}(dirname "${'$'}SX_WRAPPER")"
                echo 'Keep this Termux session running. Use Ctrl+C here to stop the wrapper.'
                termux-wake-lock
                trap 'termux-wake-unlock' EXIT
                QEMU_BIN="${'$'}PREFIX/bin/qemu-system-x86_64" "${'$'}SX_WRAPPER" --accel tcg --host 127.0.0.1 --host-port 12340
            """.trimIndent()
            Task.START_BRIDGE -> """
                [ -x ./streamx-alac ] || { echo 'Install audio tools first'; exit 1; }
                curl --fail --silent --max-time 10 http://127.0.0.1:12340/status >/dev/null || { echo 'Start the signed-in local wrapper first'; exit 1; }
                if [ -f bridge.pid ] && kill -0 "${'$'}(cat bridge.pid)" 2>/dev/null; then echo 'Local streaming is already running'; exit 0; fi
                termux-wake-lock
                STREAMX_ALAC_TOKEN=${quote(token)} nohup ./streamx-alac -wrapper http://127.0.0.1:12340 -listen 127.0.0.1:12440 > bridge.log 2>&1 < /dev/null &
                SX_PID=${'$'}!; printf '%s\n' "${'$'}SX_PID" > bridge.pid
                sleep 1
                kill -0 "${'$'}SX_PID" || { echo 'Streaming helper failed to start'; exit 1; }
                echo 'Local streaming started on this device'
            """.trimIndent()
            Task.STOP -> """
                if [ -f bridge.pid ]; then
                    SX_PID="${'$'}(cat bridge.pid)"
                    if [ -r "/proc/${'$'}SX_PID/cmdline" ] && tr '\000' ' ' < "/proc/${'$'}SX_PID/cmdline" | grep -q 'streamx-alac'; then kill "${'$'}SX_PID"; fi
                    rm -f bridge.pid
                fi
                echo 'Local streaming stopped. Stop the wrapper using Ctrl+C in its Termux session, then run termux-wake-unlock.'
            """.trimIndent()
            Task.CHECK -> """
                [ -x ./amdl ] && echo 'Downloader: installed' || echo 'Downloader: not installed'
                [ -x ./streamx-alac ] && echo 'Streaming helper: installed' || echo 'Streaming helper: not installed'
                [ -f wrapper-launcher ] && echo 'Local wrapper: installed' || echo 'Local wrapper: not installed'
                curl --fail --silent --max-time 5 http://127.0.0.1:12340/status >/dev/null && echo 'Wrapper: reachable' || echo 'Wrapper: not running'
                if [ -f bridge.pid ] && kill -0 "${'$'}(cat bridge.pid)" 2>/dev/null; then echo 'Streaming helper: running'; else echo 'Streaming helper: not running'; fi
            """.trimIndent()
            Task.LOGS -> "[ ! -f bridge.log ] || tail -n 80 bridge.log"
        }
        return preamble+"\n"+body+"\n"
    }
}
