"""Normal production JAR graphical probe, isolated from the user's actual modpack configuration."""
from pathlib import Path
import argparse,json,os,re,shutil,subprocess,sys,time
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'validation/smoke'))
from smoke_test import Server,read_password,rcon_port,server_port,CONTAINED_FAULT,MIXIN_TROUBLE,SUSPECT_EXCEPTION
from rcon import Rcon

def main():
    p=argparse.ArgumentParser();p.add_argument('--server-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True)
    p.add_argument('--java',type=Path,default=Path(r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.1+1\bin\java.exe'))
    p.add_argument('--instances-dir',type=Path,default=Path(r'L:\curseforge modpacks\Instances'))
    p.add_argument('--install-dir',type=Path,default=Path(r'L:\curseforge modpacks\Install'))
    p.add_argument("--biome",help="Test a naturally generated biome in a separate normal world, e.g. minecraft:meadow")
    args=p.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
    run=ROOT/'radar-client/run-hologram-smoke'
    for folder in ['mods','shaderpacks','config']: (run/folder).mkdir(parents=True,exist_ok=True)
    for pattern in ['iris-fabric-1.8.8+mc1.21.1.jar','sodium-fabric-0.6.13+mc1.21.1.jar']:
        source=args.instances_dir/'Cobblemon SMP/mods'/pattern;shutil.copy2(source,run/'mods'/source.name)
    for pattern in ['Cobblemon*.jar','fabric-api*.jar']:
        source=next((args.server_dir/'mods').glob(pattern));shutil.copy2(source,run/'mods'/source.name)
    (run/'mods/CobbleRaids-Radar-Client-0.1.0+1.21.1.jar').unlink(missing_ok=True)
    (run/'mods/CobbleRaids-Radar-Client-0.2.0+1.21.1.jar').unlink(missing_ok=True)
    (run/'mods/CobbleRaids-Radar-Client-0.3.0+1.21.1.jar').unlink(missing_ok=True)
    (run/'mods/CobbleRaids-Radar-Client-0.4.0+1.21.1.jar').unlink(missing_ok=True)
    (run/'mods/CobbleRaids-Radar-Client-0.4.1+1.21.1.jar').unlink(missing_ok=True)
    for source in [ROOT/'radar-client/build/libs/CobbleRaids-Radar-Client-0.4.2+1.21.1.jar',
                   ROOT/'radar-client/build/smoke-libs/CobbleRaids-Radar-Probe.jar']:
        shutil.copy2(source,run/'mods'/source.name)
    candidates=list(args.instances_dir.glob('*/shaderpacks/ComplementaryUnbound_r5.1.1.zip'))
    assert candidates,'Local ComplementaryUnbound_r5.1.1.zip not found'
    shutil.copy2(candidates[0],run/'shaderpacks'/candidates[0].name)
    (run/'config/iris.properties').write_text('enableShaders=true\nshaderPack=ComplementaryUnbound_r5.1.1.zip\n',encoding='utf-8')
    (run/'options.txt').write_text(('renderDistance:10' if args.biome else 'renderDistance:6')+'\nsimulationDistance:4\nmaxFps:60\nenableVsync:false\nguiScale:2\npauseOnLostFocus:false\nfov:0.0\nonboardAccessibility:false\ntutorialStep:none\n',encoding='utf-8')
    for name in ['graphical-result.json','graphical-failure.txt','fixture-ready']:(args.output_dir/name).unlink(missing_ok=True)
    for name in ['hologram-mainhand.png','hologram-flat-fallback.png','hologram-offhand.png']:
        screenshot=args.output_dir/'screenshots'/name
        if screenshot.exists():
            previous=args.output_dir/'previous-review'
            previous.mkdir(exist_ok=True)
            shutil.copy2(screenshot,previous/name)
            screenshot.unlink()
    metadata=json.loads((args.install_dir/'versions/1.21.1/1.21.1.json').read_text(encoding='utf-8'))
    libraries=[]
    for lib in metadata['libraries']:
        allowed=not lib.get('rules')
        for rule in lib.get('rules',[]):
            os_rule=rule.get('os',{})
            if os_rule.get('name','windows')=='windows' and os_rule.get('arch','x86_64') not in ('x86','arm64'):
                allowed=rule['action']=='allow'
        artifact=lib.get('downloads',{}).get('artifact')
        if allowed and artifact:
            jar=args.install_dir/'libraries'/artifact['path']
            if not jar.exists():
                group,module,version=lib['name'].split(':')[:3]
                cached=list((Path.home()/'.gradle/caches/modules-2/files-2.1'/group/module/version).glob('*/'+Path(artifact['path']).name))
                assert cached,jar;jar=cached[0]
            libraries.append(jar)
    for folder in ['net/fabricmc','org/ow2/asm']:
        libraries.extend((args.server_dir/'libraries'/folder).rglob('*.jar'))
    libraries.append(args.install_dir/'versions/1.21.1/1.21.1.jar')
    classpath=';'.join(str(x) for x in libraries)
    assert len(classpath)<28000,'Use an argument file for a longer classpath'
    server=Server(args.server_dir,args.java);client=None
    properties=args.server_dir/'server.properties'
    original_properties=properties.read_bytes() if args.biome else None
    forced_region=None
    try:
        if args.biome:
            assert re.fullmatch(r'minecraft:[a-z_]+',args.biome),'Expected a vanilla biome identifier'
            settings=original_properties.decode('utf-8')
            for key,value in {'level-name':'radar-natural-biome-world','level-type':'minecraft:normal',
                    'level-seed':'20261030','generator-settings':'{}','view-distance':'10'}.items():
                settings=re.sub(r'^'+re.escape(key)+r'=.*$',lambda _:key+'='+value,settings,flags=re.MULTILINE)
            properties.write_text(settings,encoding='utf-8')
        server.start();server.wait_until_ready()
        command=[str(args.java),'-Xmx4G','-Dcobbleraids.radar.smoke.output='+str(args.output_dir),
            '-Dcobbleraids.radar.smoke.biome='+str(args.biome or ''),'-Dfabric.gameVersion=1.21.1','-cp',classpath,'net.fabricmc.loader.impl.launch.knot.KnotClient',
            '--username','HologramProbe','--accessToken','0','--version','1.21.1','--gameDir',str(run),
            '--assetsDir',str(Path.home()/'.gradle/caches/fabric-loom/assets'),'--assetIndex','1.21.1-17',
            '--width','1280','--height','720','--quickPlayMultiplayer','127.0.0.1:'+str(server_port(args.server_dir))]
        with (args.output_dir/'graphical-client.log').open('w',encoding='utf-8') as log:
            client=subprocess.Popen(command,cwd=run,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            ready=False;deadline=time.monotonic()+420
            while client.poll() is None and time.monotonic()<deadline:
                with Rcon('127.0.0.1',rcon_port(args.server_dir),read_password(args.server_dir)) as rcon:
                    if not ready and 'HologramProbe' in rcon.command('list'):
                        if args.biome:
                            for cmd in ['gamemode creative HologramProbe','clear HologramProbe','time set 6000','weather clear']:
                                print(rcon.command(cmd),flush=True)
                            located=rcon.command('locate biome '+args.biome)
                            print(located,flush=True)
                            position=re.search(r'\[(-?\d+),[^,]+,\s*(-?\d+)\]',located)
                            assert position,'Could not locate natural biome: '+located
                            x,z=map(int,position.groups())
                            forced_region=(x-32,z-80,x+112,z+32)
                            print(rcon.command('forceload add '+' '.join(map(str,forced_region))),flush=True)
                            print(rcon.command(f'tp HologramProbe {x} 320 {z}'),flush=True)
                            time.sleep(8)
                            print(rcon.command(f'spreadplayers {x} {z} 0 1 false HologramProbe'),flush=True)
                            spawned=rcon.command('execute at HologramProbe positioned ~72 ~ ~-48 positioned over motion_blocking_no_leaves run cobbleraids spawn charizard ~ ~1 ~')
                            print(spawned,flush=True)
                            assert 'Spawned' in spawned,spawned
                            (args.output_dir/'biome-fixture.json').write_text(json.dumps({
                                'world':'radar-natural-biome-world','seed':20261030,'requestedBiome':args.biome,
                                'locatedX':x,'locatedZ':z,'naturalTerrain':True}),encoding='utf-8')
                            print(rcon.command('item replace entity HologramProbe hotbar.0 with cobbleraids:raid_radar'),flush=True)
                        else:
                            for cmd in ['fill -16 3 -16 16 3 16 minecraft:stone','tp HologramProbe 0.5 4 0.5',
                                'gamemode creative HologramProbe','clear HologramProbe','time set day','weather clear',
                                'forceload add 32 -160 160 -32','gamerule commandModificationBlockLimit 1000000',
                                'fill 32 4 -160 160 11 -32 minecraft:stone','fill 48 12 -144 144 19 -48 minecraft:stone',
                                'fill 64 20 -128 128 27 -64 minecraft:stone','fill 80 28 -112 112 35 -80 minecraft:stone',
                                'cobbleraids spawn charizard 96 48 -96','item replace entity HologramProbe hotbar.0 with cobbleraids:raid_radar']:
                                response=rcon.command(cmd)
                                print(response[:200],flush=True)
                                assert 'position is not loaded' not in response.lower(),response
                                if cmd.startswith('forceload add'):time.sleep(3)
                        (args.output_dir/'fixture-ready').write_text("ready",encoding="utf-8")
                        ready=True
                if (args.output_dir/'graphical-result.json').exists():break
                if (args.output_dir/'graphical-failure.txt').exists():raise AssertionError((args.output_dir/'graphical-failure.txt').read_text())
                time.sleep(2)
            assert (args.output_dir/'graphical-result.json').exists(),'Graphical probe did not finish; inspect graphical-client.log'
            client.wait(timeout=40)
    finally:
        try:
            if client and client.poll() is None:
                subprocess.run(['taskkill','/PID',str(client.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if server.process and server.process.poll() is None:
                with Rcon('127.0.0.1',rcon_port(args.server_dir),read_password(args.server_dir)) as rcon:
                    if forced_region:rcon.command('forceload remove '+' '.join(map(str,forced_region)))
                    elif not args.biome:rcon.command('forceload remove 32 -160 160 -32')
        finally:
            try:server.stop()
            finally:
                if original_properties is not None:properties.write_bytes(original_properties)
    for regex in (CONTAINED_FAULT,MIXIN_TROUBLE,SUSPECT_EXCEPTION):assert not regex.search(server.read_log()),regex.pattern
    print('GRAPHICAL HOLOGRAM: PASS',flush=True)
if __name__=='__main__':main()
