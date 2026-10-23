#!/usr/bin/env python3
"""Resolve verified Termux runtime packages for an APK-bundled QEMU (build time only)."""
import concurrent.futures, hashlib, json, pathlib, re, subprocess, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]
CACHE=ROOT/'.debug/apple-qemu'
CACHE.mkdir(parents=True,exist_ok=True)
lock=ROOT/'tools/apple-qemu/runtime.lock.json'
if lock.exists():
    packages={p['Package']:p for p in json.loads(lock.read_text())['packages']}
else:
    with urllib.request.urlopen('https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages',timeout=120) as response:
        index=response.read().decode()
    packages={}
    for block in index.split('\n\n'):
        fields={}
        for line in block.splitlines():
            if ': ' in line and not line.startswith(' '):
                k,v=line.split(': ',1);fields[k]=v
        if 'Package' in fields:packages[fields['Package']]=fields

selected={}
def resolve(name):
    if name in selected or name not in packages:return
    p=packages[name];selected[name]=p
    for dep in p.get('Depends','').split(','):
        resolve(dep.strip().split(' ')[0])
resolve('qemu-system-x86-64-headless')
print(len(selected),'packages,',sum(int(p.get('Size',0)) for p in selected.values())//1048576,'MiB',flush=True)
def download(p):
    filename=p['Filename'];dest=CACHE/pathlib.Path(filename).name
    if not dest.exists() or hashlib.file_digest(dest.open('rb'),'sha256').hexdigest()!=p['SHA256']:
        tmp=dest.with_suffix('.part')
        req=urllib.request.Request('https://packages.termux.dev/apt/termux-main/'+urllib.parse.quote(filename,safe='/'),headers={'User-Agent':'StreamX-QEMU-build'})
        with urllib.request.urlopen(req,timeout=120) as src,tmp.open('wb') as out:
            import shutil
            shutil.copyfileobj(src,out)
        assert hashlib.file_digest(tmp.open('rb'),'sha256').hexdigest()==p['SHA256'],p['Package']
        tmp.replace(dest)
    root=CACHE/'root';root.mkdir(exist_ok=True)
    member=next(x for x in subprocess.check_output(['ar','t',str(dest)],text=True).splitlines() if x.startswith('data.tar'))
    archive=subprocess.Popen(['ar','p',str(dest),member],stdout=subprocess.PIPE)
    subprocess.run(['bsdtar','-xf','-','-C',str(root)],stdin=archive.stdout,check=True)
    archive.stdout.close();assert archive.wait()==0
    print(p['Package'],flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:list(pool.map(download,selected.values()))
(CACHE/'packages.lock.json').write_text(json.dumps(list(selected.values()),indent=2))
