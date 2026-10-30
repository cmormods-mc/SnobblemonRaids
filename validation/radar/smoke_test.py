"""Craft and operate the radar through a vanilla protocol client on a dedicated test server.

Install the prototype jar in --server-dir first. Uses validation/smoke's existing npm dependencies.
Never point this at a production server/world: it boots/stops the specified rig and changes its world.
"""
from pathlib import Path
import argparse, json, queue, subprocess, sys, threading, time
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'smoke'))
from smoke_test import Server, read_password, rcon_port, server_port, CONTAINED_FAULT, MIXIN_TROUBLE, SUSPECT_EXCEPTION, COMMAND_ERROR
from rcon import Rcon

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--server-dir',type=Path,required=True)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args()
    args.output_dir.mkdir(parents=True,exist_ok=True)
    server=Server(args.server_dir,args.java)
    bot=None
    events=queue.Queue()
    messages=[]
    def pump():
        for line in bot.stdout:
            messages.append(line)
            if line.startswith('RADAR:'):
                events.put(json.loads(line[6:]))
    def wait(predicate,timeout=45):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            try: event=events.get(timeout=.25)
            except queue.Empty:
                if bot.poll() is not None: raise AssertionError('Radar bot exited: '+''.join(messages[-8:]))
                continue
            if predicate(event): return event
        raise AssertionError('Timed out waiting for bot: '+''.join(messages[-8:]))
    request_id=0
    def action(action,**kwargs):
        nonlocal request_id
        request_id+=1
        bot.stdin.write(json.dumps(dict(action=action,id=request_id,**kwargs))+'\n');bot.stdin.flush()
        response=wait(lambda e:e.get('event')=='result' and e.get('id')==request_id)
        assert response['ok'],response
        return response
    checks=[]
    try:
        server.start();server.wait_until_ready()
        bot=subprocess.Popen(['node',str(HERE/'radar_bot.js'),str(server_port(args.server_dir))],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        threading.Thread(target=pump,daemon=True).start()
        wait(lambda e:e.get('event')=='ready',90)
        with Rcon('127.0.0.1',rcon_port(args.server_dir),read_password(args.server_dir)) as rcon:
            def command(text):
                response=rcon.command(text)
                print(text.split(' ')[0]+': '+response[:220],flush=True)
                assert not COMMAND_ERROR.search(response),response
                return response
            command('gamemode survival RadarProbe')
            command('fill -16 3 -16 16 3 16 minecraft:stone')
            command('tp RadarProbe 0.5 4 0.5')
            command('setblock 1 4 0 minecraft:crafting_table')
            command('clear RadarProbe')
            for item,count in [('minecraft:netherite_ingot',4),('minecraft:shulker_shell',2),('minecraft:compass',1),('cobblemon:upgrade',2)]:
                command(f'give RadarProbe {item} {count}')
            time.sleep(2)
            command('data get entity RadarProbe Inventory')
            action('craft');time.sleep(1)
            data=command('data get entity RadarProbe Inventory')
            assert 'cobbleraids:raid_radar' in data,data
            for item in ['minecraft:netherite_ingot','minecraft:shulker_shell','cobblemon:upgrade','minecraft:compass']:
                assert f'id: "{item}"' not in data,data
            checks.append('Real 3x3 recipe crafts one server-side radar and consumes the exact ingredients')
            action('equip')
            time.sleep(1)
            action('use')
            time.sleep(3)
            stats=action('stats')
            assert stats['fullPackets']>=1 and stats['held']=='filled_map',stats
            checks.append('Open radar is a vanilla filled map and receives a full initial texture')
            # Make a visible mound; terrain refresh must eventually alter the cached texture.
            command('fill 16 4 16 48 15 48 minecraft:stone')
            command('cobbleraids spawn charizard 96 4 -96')
            command('cobbleraids spawn venusaur -100 4 96')
            time.sleep(3)
            assert action('stats')['orange'],'No raid target drawn'
            checks.append('Loaded admin raid bosses appear as orange targets')
            selected_before=action('stats')['packets']
            action('use',sneak=True);time.sleep(1)
            assert action('stats')['packets']>selected_before,'Cycling target did not change pixels'
            checks.append('Sneak-use cycles to the other live raid')
            time.sleep(10)
            action('save',path=str(args.output_dir/'radar-live-map.bin'))
            command('tp RadarProbe 30 16 30')
            time.sleep(1)
            moving=action('stats')['packets']
            assert moving>stats['packets'],'Movement sent no update'
            checks.append('Movement produces updated map pixels')
            time.sleep(1)
            action('use');time.sleep(2)
            closed=action('stats')
            assert closed['held']=='compass',closed
            time.sleep(2)
            assert action('stats')['packets']==closed['packets'],'Closed radar still sends packets'
            checks.append('Close restores the compass and stops map traffic')
            action('equip',offhand=True);time.sleep(1)
            action('use',offhand=True)
            time.sleep(2)
            assert action('stats')['packets']>closed['packets'],'Offhand radar received no map'
            checks.append('Offhand radar opens and receives its texture')
            assert action('stats')['hologramFrames']==0,'Optional data leaked to a client without the companion channel'
            checks.append('Clients without the companion receive no hologram packets')
            action('advertise');time.sleep(3)
            hologram=action('stats')
            assert hologram['hologramFullFrames']>=1 and hologram['knownSamples']>0,hologram
            assert hologram['hologramFrames']>hologram['hologramFullFrames'],hologram
            action('save_hologram',path=str(args.output_dir/'radar-hologram-scene.json'))
            checks.append('Advertised companion receives a bounded terrain snapshot and lighter pose updates')
            # Drop connection to exercise per-player cleanup; shutdown checks catch contained faults.
            print('RADAR CHECKS: '+json.dumps(checks),flush=True)
    finally:
        if bot is not None:
            bot.terminate()
            try: bot.wait(timeout=8)
            except subprocess.TimeoutExpired: bot.kill();bot.wait()
        server.stop()
        (args.output_dir/'radar-bot.log').write_text(''.join(messages),encoding='utf-8')
    log=server.read_log()
    for pattern in (CONTAINED_FAULT,MIXIN_TROUBLE,SUSPECT_EXCEPTION):
        assert not pattern.search(log), 'Server fault matched: '+pattern.pattern
    (args.output_dir/'radar-smoke-result.json').write_text(json.dumps(dict(checks=checks,passed=True),indent=2),encoding='utf-8')
    print('RADAR SMOKE: PASS',flush=True)

if __name__=='__main__':main()
