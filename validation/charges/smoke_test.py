"""Real vanilla-client UI, ordinary raid starts, UUID persistence and offline refill. Isolated rig only."""
from pathlib import Path
import argparse,hashlib,json,queue,subprocess,sys,threading,time,uuid
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'validation/smoke'))
from smoke_test import Server,read_password,rcon_port,server_port,CONTAINED_FAULT,MIXIN_TROUBLE,SUSPECT_EXCEPTION
from rcon import Rcon

def main():
 p=argparse.ArgumentParser();p.add_argument('--server-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True)
 p.add_argument('--java',type=Path,default=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.12.1+1/bin/java.exe'))
 args=p.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
 name='Charge'+str(int(time.time()))[-8:]
 raw=bytearray(hashlib.md5(('OfflinePlayer:'+name).encode()).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128
 player_id=str(uuid.UUID(bytes=bytes(raw)))
 data=args.server_dir/'radar-test-world/data/cobbleraids_raid_charges.dat'
 server=Server(args.server_dir,args.java);bot=None;messages=[];events=queue.Queue();serial=0;checks=[]
 def pump():
  for line in bot.stdout:
   messages.append(line)
   if line.startswith('RADAR:'):events.put(json.loads(line[6:]))
 def wait(predicate,timeout=60):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   try:event=events.get(timeout=.25)
   except queue.Empty:continue
   if predicate(event):return event
  raise AssertionError('Bot timeout: '+''.join(messages[-6:]))
 def action(action,**kwargs):
  nonlocal serial
  serial+=1;bot.stdin.write(json.dumps(dict(action=action,id=serial,**kwargs))+'\n');bot.stdin.flush()
  value=wait(lambda event:event.get('event')=='result' and event.get('id')==serial)
  assert value['ok'],value
  return value
 def connect():
  nonlocal bot
  bot=subprocess.Popen(['node',str(ROOT/'validation/radar/radar_bot.js'),str(server_port(args.server_dir)),name],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
  threading.Thread(target=pump,daemon=True).start();wait(lambda event:event.get('event')=='ready',120)
 def disconnect():
  nonlocal bot
  if bot:
   bot.terminate()
   try:bot.wait(timeout=8)
   except subprocess.TimeoutExpired:bot.kill();bot.wait()
   bot=None
 def account(age=None):
  command=['node',str(ROOT/'validation/charges/saved_account.js'),str(data),player_id]
  if age is not None:command.append(str(age))
  return json.loads(subprocess.check_output(command,text=True,encoding='utf-8'))
 def open_menu():
  time.sleep(.6);action('use',menu=True);time.sleep(.4)
  menu=action('menu');assert menu.get('slots'),menu
  return menu
 try:
  server.start();server.wait_until_ready();connect()
  with Rcon('127.0.0.1',rcon_port(args.server_dir),read_password(args.server_dir)) as rcon:
   def command(text):
    response=rcon.command(text);print(response[:220],flush=True);return response
   for cmd in [f'gamemode creative {name}','fill -16 3 -16 16 3 16 minecraft:stone',f'tp {name} 0.5 4 0.5',f'clear {name}',f'give {name} cobbleraids:raid_radar']:
    command(cmd)
   action('equip');menu=open_menu()
   assert menu['slots'][4]['name']=='emerald' and '3/3' in json.dumps(menu.get('title')),menu
   assert 'Fully charged' in json.dumps(menu['slots'][14]),menu['slots'][14]
   for slot,mode in [(10,0),(11,1),(12,4)]:action('menu_click',slot=slot,mode=mode)
   assert not action('menu').get('cursor'),'Menu item could be taken'
   inventory=command(f'data get entity {name} Inventory')
   assert 'amethyst_shard' not in inventory and 'gray_dye' not in inventory,inventory
   checks.append('Right-click menu shows 3/3 and prevents pickup, shift-click and throw of charge icons')
   action('menu_click',slot=22);time.sleep(2)
   assert action('stats')['held']=='filled_map'
   checks.append('Menu opens the real radar map without a client mod')
   command('cobbleraids despawn all');command('cobbleraids spawn charizard 3 4 0')
   assert 'STARTED_RECRUITMENT' in command(f'cobbleraids debug join {name}')
   time.sleep(1);assert account()['available']==3
   command('cobbleraids despawn all');assert account()['available']==3
   checks.append('Joining then cancelling a lobby spends no charge')
   command(f'pokegiveother {name} pikachu level=100')
   deadline=None
   for number in range(1,4):
    command(f'pokeheal {name}');command('cobbleraids spawn charizard 3 4 0')
    assert 'STARTED_RECRUITMENT' in command(f'cobbleraids debug join {name}')
    command(f'cobbleraids debug join {name}')
    end=time.monotonic()+50
    while time.monotonic()<end:
     saved=account()
     if saved['available']==3-number:break
     time.sleep(1)
    else:raise AssertionError('Successful battle did not consume exactly one charge')
    if deadline is None:deadline=saved['next_refill']
    assert saved['next_refill']==deadline,'Repeated entry reset the hourly timer'
    assert 'No active' not in command('cobbleraids debug battle')
    command('cobbleraids despawn all');time.sleep(2)
   checks.append('Three genuine raid starts spend exactly three charges without resetting the refill deadline')
   command('cobbleraids spawn charizard 3 4 0')
   assert 'NO_CHARGES' in command(f'cobbleraids debug join {name}')
   command('cobbleraids despawn all')
   menu=open_menu();assert menu['slots'][4]['name']=='redstone';assert 'Next charge in' in json.dumps(menu['slots'][14])
   before=json.dumps(menu['slots'][14]);time.sleep(2.2);after=json.dumps(action('menu')['slots'][14]);assert before!=after,'Countdown did not update'
   checks.append('Fourth raid is refused; menu shows zero charges and a live refill countdown')
   action('close_menu');command(f'clear {name} cobbleraids:raid_radar');command(f'give {name} cobbleraids:raid_radar')
   assert open_menu()['slots'][4]['name']=='redstone';action('close_menu')
   checks.append('Replacing the physical radar does not reset UUID charges')
  disconnect();server.stop()
  server.start();server.wait_until_ready();connect();assert open_menu()['slots'][4]['name']=='redstone';action('close_menu')
  assert account()['next_refill']==deadline
  checks.append('Relog and full server restart preserve the depleted pool and original refill time')
  disconnect();server.stop();account(age=900)
  server.start();server.wait_until_ready();connect();menu=open_menu()
  assert '1 / 3' in json.dumps(menu['slots'][4]),menu['slots'][4]
  assert '44m' in json.dumps(menu['slots'][14]) or '45m' in json.dumps(menu['slots'][14]),menu['slots'][14]
  checks.append('Elapsed offline time restores one charge and preserves the partial hour toward the next')
  action('close_menu');disconnect();server.stop();account(age=8000)
  server.start();server.wait_until_ready();connect();menu=open_menu()
  assert '3 / 3' in json.dumps(menu['slots'][4]) and 'Fully charged' in json.dumps(menu['slots'][14]),menu
  checks.append('Long offline time refills to three without exceeding the cap')
  (args.output_dir/'charge-smoke-result.json').write_text(json.dumps(dict(passed=True,player=name,uuid=player_id,checks=checks),indent=2),encoding='utf-8')
 finally:
  disconnect();server.stop();(args.output_dir/'charge-bot.log').write_text(''.join(messages),encoding='utf-8')
 for pattern in (CONTAINED_FAULT,MIXIN_TROUBLE,SUSPECT_EXCEPTION):assert not pattern.search(server.read_log()),pattern.pattern
 print('RAID CHARGE SMOKE: PASS',flush=True)
if __name__=='__main__':main()
