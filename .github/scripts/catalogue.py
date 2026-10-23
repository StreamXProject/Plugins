#!/usr/bin/env python3
"""Build a v1 StreamX catalogue from actual signed APKs, never guessed metadata."""
import argparse,hashlib,json,os,pathlib,re,shutil,subprocess
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--tag',required=True);p.add_argument('--output',default='dist');p.add_argument('--root',default='.');args=p.parse_args()
root=pathlib.Path(args.root).resolve();out=pathlib.Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','/opt/android-sdk')))
buildtools=sorted((sdk/'build-tools').iterdir(),reverse=True)
aapt=next(folder/'aapt' for folder in buildtools if (folder/'aapt').exists())
signer=next(folder/'apksigner' for folder in buildtools if (folder/'apksigner').exists())
entries=[]
for extension in json.loads((root/'extensions.json').read_text()):
 before=len(entries)
 apks=sorted((root/extension['project']/'app/build/outputs/apk/release').glob('*.apk'))
 # Collector jobs can place their APKs together while preserving package metadata.
 apks+=sorted((root/'artifacts'/extension['id']).rglob('*.apk')) if (root/'artifacts'/extension['id']).exists() else []
 if not apks:raise SystemExit('No APK built for '+extension['id'])
 for apk in apks:
  badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
  pkg=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",badging)
  if not pkg or pkg[1]!=extension['packageName']:raise SystemExit('Unexpected package in '+str(apk))
  certs=subprocess.check_output([str(signer),'verify','--print-certs',str(apk)],text=True)
  fingerprints=re.findall(r'certificate SHA-256 digest: ([0-9a-f]+)',certs)
  if len(fingerprints)!=1:raise SystemExit('Exactly one verified signer is required')
  abi=re.search(r"native-code: ([^\n]+)",badging)
  abis=re.findall("'([^']+)'",abi[1]) if abi else []
  # Native plugins publish only individual ABIs. Portable extensions need no ABI suffix.
  if len(abis)>1:continue
  label=re.search(r"application-label:'([^']+)'",badging)
  name=re.sub(r'(?i)\s+skin$','',label[1] if label else extension['id'])
  if not re.fullmatch(r'[A-Za-z0-9._+-]+',pkg[3]):raise SystemExit('Unsafe version name')
  suffix='-'+abis[0] if abis else ''
  filename=f'streamx-{extension["id"]}-v{pkg[3]}{suffix}.apk'
  target=out/filename
  shutil.copy2(apk,target)
  entries.append(dict(id=extension['id'],name=name,packageName=pkg[1],versionCode=int(pkg[2]),versionName=pkg[3],type=extension['type'],abis=abis,minSdk=int(re.search(r"sdkVersion:'(\d+)'",badging)[1]),apkUrl=f'https://github.com/{args.repo}/releases/download/{args.tag}/{filename}',sha256=hashlib.file_digest(target.open('rb'),'sha256').hexdigest(),certificateSha256=fingerprints[0],size=target.stat().st_size))
 if len(entries)==before:raise SystemExit('No portable or individual-ABI APK built for '+extension['id'])
index=dict(schemaVersion=1,name='StreamX '+args.repo.split('/')[-1],extensions=entries)
(out/'index.json').write_text(json.dumps(index,indent=2)+'\n')
(out/'index.min.json').write_text(json.dumps(index,separators=(',',':'))+'\n')
print(f'Verified {len(entries)} signed APKs')
