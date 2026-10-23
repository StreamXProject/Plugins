#!/usr/bin/env python3
"""Bundle only QEMU's ELF dependency closure; all native code lives in the APK."""
import hashlib,json,os,pathlib,re,shutil,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]
CACHE=ROOT/'.debug/apple-qemu'
PREFIX=CACHE/'root/data/data/com.termux/files/usr'
OUT=ROOT/'app/src/main/jniLibs/arm64-v8a'
ASSETS=ROOT/'app/src/main/assets/qemu-firmware'
PATCH=os.environ.get('PATCHELF') or shutil.which('patchelf') or str(CACHE/'python/bin/patchelf')
if not pathlib.Path(PATCH).is_file():
    subprocess.run(['uv','pip','install','--target',str(CACHE/'python'),'patchelf==0.19.1.0'],check=True)
SYSTEM={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libOpenSLES.so','libaaudio.so','libEGL.so','libGLESv2.so','libvulkan.so','libmediandk.so'}
selected={}
def needed(file):
    return re.findall(r'\(NEEDED\).*?\[(.*?)\]',subprocess.check_output(['readelf','-d',str(file)],text=True))
def name(raw):return 'libsxq_'+raw.removeprefix('lib').replace('.so.','_v').removesuffix('.so')+'.so'
def resolve(raw):
    if raw in SYSTEM or raw in selected:return
    paths=list((PREFIX/'lib').glob(raw))
    if not paths:paths=list((PREFIX/'lib').rglob(raw))
    if not paths:raise RuntimeError('Unresolved native dependency '+raw)
    file=paths[0].resolve();selected[raw]=file
    for child in needed(file):resolve(child)
exe=PREFIX/'bin/qemu-system-x86_64'
for dep in needed(exe):resolve(dep)
OUT.mkdir(parents=True,exist_ok=True)
for old in OUT.glob('libsxq_*.so'):old.unlink()
for raw,src in selected.items():
    dst=OUT/name(raw);shutil.copyfile(src,dst)
    args=[PATCH,'--set-soname',name(raw),'--set-rpath','$ORIGIN']
    for dep in needed(src):
        if dep not in SYSTEM:args+=['--replace-needed',dep,name(dep)]
    subprocess.run(args+[str(dst)],check=True)
    dst.chmod(0o755)
dst=OUT/'libqemu_exec.so';shutil.copyfile(exe,dst)
args=[PATCH,'--set-rpath','$ORIGIN']
for dep in needed(exe):
    if dep not in SYSTEM:args+=['--replace-needed',dep,name(dep)]
subprocess.run(args+[str(dst)],check=True);dst.chmod(0o755)
ASSETS.mkdir(parents=True,exist_ok=True)
for firmware in ['bios.bin','bios-256k.bin','kvmvapic.bin','vgabios-stdvga.bin','efi-e1000.rom','linuxboot_dma.bin','multiboot_dma.bin','pvh.bin']:
    shutil.copyfile(PREFIX/'share/qemu'/firmware,ASSETS/firmware)
manifest={'qemu':'11.1.2','packages':json.loads((CACHE/'packages.lock.json').read_text()),'libraries':list(selected),
          'native_sha256':{p.name:hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in OUT.glob('libsxq_*.so')}}
(ROOT/'tools/apple-qemu/runtime.lock.json').write_text(json.dumps(manifest,indent=2))
print(len(selected),'native dependencies;',sum(p.stat().st_size for p in OUT.glob('libsxq_*.so'))//1048576,'MiB')
