"""Inspect the real artifacts: server-only base, optional client, no shipped test probe/shader overrides."""
from pathlib import Path
import json,re,zipfile
ROOT=Path(__file__).resolve().parents[2]
server_version=re.search(r"version = '([^']+)'",(ROOT/'build.gradle').read_text(encoding='utf-8')).group(1)
client_version=re.search(r"version = '([^']+)'",(ROOT/'radar-client/build.gradle').read_text(encoding='utf-8')).group(1)
server=ROOT/'build/libs'/f'CobbleRaids-{server_version}.jar'
client=ROOT/'radar-client/build/libs'/f'CobbleRaids-Radar-Client-{client_version}.jar'
with zipfile.ZipFile(server) as z:
    names=z.namelist();manifest=json.loads(z.read('fabric.mod.json'))
    assert 'client' not in manifest['entrypoints']
    assert 'cobbleraids_radar' not in manifest['depends']
    assert not any(n.startswith('com/cobbleraids/radarclient/') for n in names)
    assert 'com/cobbleraids/radar/protocol/RadarHologramPayload.class' in names
with zipfile.ZipFile(client) as z:
    names=z.namelist();manifest=json.loads(z.read('fabric.mod.json'))
    assert manifest['environment']=='client'
    assert 'main' not in manifest['entrypoints']
    assert 'cobbleraids' not in manifest['depends']
    assert not any(n.startswith('com/cobbleraids/radarprobe/') for n in names)
    assert not any(n.startswith('com/cobbleraids/raid/') for n in names)
    assert not any(n.startswith('assets/minecraft/shaders/') for n in names)
    assert 'assets/cobbleraids_radar/textures/white.png' in names
    config=json.loads(z.read('cobbleraids-radar.mixins.json'))
    for mixin in config['client']:
        assert 'com/cobbleraids/radarclient/mixin/'+mixin+'.class' in names
print('Hologram artifact boundary: PASS -- separate optional client, common protocol, no core shader overrides or test probe')
