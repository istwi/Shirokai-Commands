#!/usr/bin/env python3
"""Reject mismatched tags, missing changelogs and non-semantic versions."""
import pathlib
import json
import re
import sys
root = pathlib.Path(__file__).resolve().parent.parent
tag = sys.argv[1]
version = re.search(r"^version = '([^']+)'", (root / 'build.gradle').read_text(), re.M)[1]
if not re.fullmatch(r'v\d+\.\d+\.\d+', tag) or tag[1:] != version.split('+')[0]:
    sys.exit(f'Tag {tag!r} does not match project version {version!r}')
if f'## {tag[1:]}' not in (root / 'CHANGELOG.md').read_text():
    sys.exit(f'Missing changelog entry for {tag}')
readiness = json.loads((root / 'docs/release-readiness.json').read_text())
required = ('dedicatedServer', 'unitAndIntegration', 'luckPermsLive', 'vanillaClientSession')
missing = [key for key in required if readiness.get(key) is not True]
if readiness.get('version') != tag[1:] or missing:
    sys.exit('Release blocked: complete and document acceptance checks: ' + ', '.join(missing))
print(f'Validated {tag} for {version}')
