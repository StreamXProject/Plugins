#!/usr/bin/env python3
"""Prevent an older workflow rerun from downgrading the discovery index."""
import json, sys
from pathlib import Path
previous=json.loads(Path(sys.argv[1]).read_text())
proposed=json.loads(Path(sys.argv[2]).read_text())
versions={entry["packageName"]:entry["versionCode"] for entry in proposed["extensions"]}
if any(versions.get(entry["packageName"],float("inf")) < entry["versionCode"] for entry in previous["extensions"]):
    sys.exit(1)
