#!/usr/bin/env python3
"""Run actual Fabric dedicated-server assertions in an isolated generated world."""
import json
import os
import signal
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
run = root / 'run' / 'integration'
config = run / 'config' / 'shirokai-commands.json'
config.parent.mkdir(parents=True, exist_ok=True)
commands = {
    'scthidden': {'mode': 'hide'},
    '/sctwand': {'mode': 'block'},
    '/scthidden': {'mode': 'hide'},
    '/sctoriginal': {'mode': 'replace', 'alias': 'sctslashalias'},
    'sctcontainer': {'mode': 'block'},
    'sctcontainer allowed': {'mode': 'allow'},
    'sctblocked': {'mode': 'block'},
    'sctbalance': {'mode': 'replace', 'alias': 'sctmoney'},
    'sctdenied': {'mode': 'replace', 'alias': 'sctrestricted'},
    'sctkit admin': {'mode': 'block'},
    'sctecho fixed': {'mode': 'replace', 'alias': 'sctfixed'},
    'sctfirst': {'mode': 'replace', 'alias': 'sctgroup first'},
    'sctsecond': {'mode': 'replace', 'alias': 'sctgroup second'},
}
config.write_text(json.dumps({'configVersion': 1, 'settings': {'opBypass': True},
                              'messages': {}, 'commands': commands, 'tabFilters': [{'command': 'sctpay', 'argumentIndex': 3, 'hidePrefixes': ['impactor:']}] }, indent=2))
(run / 'eula.txt').write_text('eula=true\n')
(run / 'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=25575\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nmax-tick-time=120000\n')
result = run / 'test-result.txt'
result.unlink(missing_ok=True)
process = subprocess.Popen([str(root / 'gradlew'), 'runIntegrationServer', '--console=plain'],
                           cwd=root, start_new_session=True)
try:
    status = process.wait(timeout=360)
except subprocess.TimeoutExpired:
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    sys.exit('FAIL: dedicated-server integration test exceeded 360 seconds')
if status:
    sys.exit(status)
if not result.exists() or not result.read_text().startswith('PASS '):
    sys.exit(result.read_text() if result.exists() else 'FAIL: no test-result.txt from this run')
print(result.read_text())
