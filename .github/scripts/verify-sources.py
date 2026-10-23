#!/usr/bin/env python3
import pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
files=subprocess.check_output(['git','ls-files'],cwd=root,text=True).splitlines()
blocked=[p for p in files if pathlib.Path(p).suffix in {'.jks','.keystore','.apk','.so'} or pathlib.Path(p).name in {'keystore.properties','local.properties','.env'}]
if blocked:raise SystemExit('Private/generated files must not be tracked: '+', '.join(blocked))
print('Source hygiene passed')
