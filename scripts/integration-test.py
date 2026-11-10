#!/usr/bin/env python3
"""Run actual Fabric dedicated-server assertions in an isolated generated world."""
import json
import shutil
import os
import signal
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
run = root / 'run' / 'integration'
config = run / 'config' / 'shirokai-commands.json'
config.parent.mkdir(parents=True, exist_ok=True)
# This generated directory belongs exclusively to the dedicated test runner.
shutil.rmtree(config.parent / 'shirokai-commands', ignore_errors=True)
commands = {
    'scthidden': {'mode': 'hide'},
    'sctrouteblocked': {'mode': 'block'},
    'sctroutehidden': {'mode': 'hide'},
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
    'sctaliaspay': {'mode': 'replace', 'alias': 'sctsend'},
}
argument_aliases = {
    'home_direct': {'alias': 'scthome {name}', 'target': 'scthome tp {name}', 'extendExisting': True},
    'ordered_routeblocked': {'alias': 'sctrouteblocked {amount}', 'target': 'sctpay {amount} Alice cash'},
    'ordered_routehidden': {'alias': 'sctroutehidden {amount}', 'target': 'sctpay {amount} Alice cash'},
    'ordered_item': {'alias': 'sctitems {item} {player}', 'target': 'sctitem {player} {item}'},
    'ordered_cash': {'alias': 'sctpay cash {amount} {player}', 'target': 'sctpay {amount} {player} cash', 'extendExisting': True},
    'ordered_tokens': {'argumentLabel': 'quantidade_jogador', 'alias': 'sctpay tokens {amount} {player}', 'target': 'sctpay {amount} {player} tokens', 'extendExisting': True},
    'ordered_dollars': {'alias': 'sctpay dollars {amount} {player}', 'target': 'sctpay {amount} {player} dollars', 'extendExisting': True},
    'ordered_other': {'alias': 'sctmessage {message} {player}', 'target': 'sctmsg {player} {message}'},
    'ordered_denied': {'alias': 'sctordereddenied {amount}', 'target': 'sctdenied {amount}'},
    'ordered_blocked': {'alias': 'sctorderedblocked', 'target': 'sctblocked'},
    'ordered_static': {'alias': 'sctstatic {currency} {amount} {player}', 'target': 'sctpay {amount} {player} {currency}', 'suggestions': {'currency': ['cash', 'tokens', 'dollars', 'impactor:cash']}}
}
filters = [
    {'command': 'sctpay', 'argumentIndex': 3, 'hidePrefixes': ['impactor:']},
    {'command': 'sctpay', 'argumentIndex': 2, 'onlinePlayersOnly': True},
    {'command': 'sctaliaspay', 'argumentIndex': 2, 'onlinePlayersOnly': True},
    {'command': 'scteconomy pay', 'argumentIndex': 2, 'onlinePlayersOnly': True},
    {'command': 'sctmsg', 'argumentIndex': 1, 'onlinePlayersOnly': True},
    {'command': 'sctafterpay', 'argumentIndex': 2, 'onlinePlayersOnly': True},
    {'command': 'sctafterpay', 'argumentIndex': 3, 'hidePrefixes': ['impactor:']},
    {'command': 'sctafterpay', 'argumentIndex': 4, 'hideAll': True}
]
config.write_text(json.dumps({'configVersion': 1, 'settings': {'opBypass': True},
                             'messages': {}, 'commands': commands, 'tabFilters': filters,
                             'argumentAliases': argument_aliases}, indent=2))
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
