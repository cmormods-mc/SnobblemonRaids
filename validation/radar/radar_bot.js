// Vanilla-protocol probe. No client CobbleRaids, Polymer, Iris or WebGUI code is installed here.
const mineflayer = require('../smoke/node_modules/mineflayer');
const readline = require('readline');
const fs = require('fs');
const bot = mineflayer.createBot({host:'127.0.0.1',port:Number(process.argv[2]),username:process.argv[3]||'RadarProbe',version:'1.21.1',auth:'offline',checkTimeoutInterval:120000});
const emit = data => console.log('RADAR:' + JSON.stringify(data));
const maps = new Map();
let packets = 0, fullPackets = 0;
let hologramFrames=0, hologramFullFrames=0, knownSamples=0;
let hologramScene=null;
bot._client.on('custom_payload',packet=>{
  if(packet.channel!=='cobbleraids:radar_hologram_v1') return;
  hologramFrames++;
  const b=Buffer.from(packet.data);
  const scene={mapId:b.readInt32BE(0),centerX:b.readInt32BE(4),centerZ:b.readInt32BE(8),baseY:b.readInt32BE(12)};
  let offset=17;
  if(b[16]) {
    hologramFullFrames++;
    scene.heights=[];
    for(let i=0;i<1089;i++) {const h=b.readInt16BE(offset);offset+=2;scene.heights.push(h===-32768?-2147483648:h);}
    knownSamples=Math.max(knownSamples,scene.heights.filter(h=>h!==-2147483648).length);
  }
  scene.hasTarget=!!b[offset++];
  if(scene.hasTarget) {scene.targetX=b.readDoubleBE(offset);scene.targetY=b.readDoubleBE(offset+8);scene.targetZ=b.readDoubleBE(offset+16);}
  hologramScene={...hologramScene,...scene};
});
bot.once('spawn', () => emit({event:'ready'}));
bot.on('error', err => emit({event:'error',message:err.message}));
bot.on('kicked', reason => emit({event:'kicked',reason}));
bot._client.on('map', packet => {
  packets++;
  if (packet.columns === 128 && packet.rows === 128) fullPackets++;
  let pixels = maps.get(packet.itemDamage);
  if (!pixels) { pixels = Buffer.alloc(128*128); maps.set(packet.itemDamage,pixels); }
  if (packet.columns) for (let row=0;row<packet.rows;row++) {
    Buffer.from(packet.data).copy(pixels,(packet.y+row)*128+packet.x,row*packet.columns,(row+1)*packet.columns);
  }
  emit({event:'map',mapId:packet.itemDamage,columns:packet.columns,rows:packet.rows});
});
readline.createInterface({input:process.stdin}).on('line', async line => {
  let request;
  try {
    request=JSON.parse(line);
    let result={};
    switch(request.action) {
      case 'craft': {
        const Vec3 = require('../smoke/node_modules/vec3').Vec3;
        const table=bot.blockAt(new Vec3(1,4,0));
        if (!table || table.name !== 'crafting_table') throw Error('Crafting table not loaded');
        // Place exact slots: custom Cobblemon items need not exist in the probe's vanilla registry.
        // RCON supplies N,S,C,U in hotbar order; a crafting table maps these to slots 37..40.
        const window=await bot.openBlock(table);
        // Ask for an authoritative resync after each click. Mineflayer's prediction merges
        // custom Polymer ingredient representations that can share a vanilla base item.
        const click=async (slot,mouseButton) => {
          bot._client.write('window_click',{windowId:window.id,stateId:-1,slot,mouseButton,mode:0,
            changedSlots:[],cursorItem:{itemCount:0}});
          await new Promise(resolve=>setTimeout(resolve,150));
        };
        for (const [source,destinations] of [[37,[1,3,7,9]],[38,[2,8]],[39,[5]],[40,[4,6]]]) {
          if (!window.slots[source]) throw Error('Ingredient slot empty: '+source);
          await click(source,0);
          for(const destination of destinations) await click(destination,1);
        }
        await new Promise(resolve=>setTimeout(resolve,500));
        if(!window.slots[0]) throw Error('Server offered no result for the radar grid');
        await click(0,0);
        await click(37,0);
        bot.closeWindow(window);
        result={items:bot.inventory.items().map(i=>({name:i.name,count:i.count}))};
        break;
      }
      case 'equip': {
        bot.setQuickBarSlot(0);
        if(request.offhand) {
          bot._client.write('block_dig',{status:6,location:{x:0,y:0,z:0},face:0,sequence:0});
          await new Promise(resolve=>setTimeout(resolve,250));
        }
        break;
      }
      case 'use':
        bot.setControlState('sneak',!!request.sneak);
        bot.activateItem(!!request.offhand);
        await new Promise(resolve=>setTimeout(resolve,150));
        bot.setControlState('sneak',false);
        if(!request.menu && bot.currentWindow) {
          bot._client.write('window_click',{windowId:bot.currentWindow.id,stateId:-1,slot:22,mouseButton:0,mode:0,changedSlots:[],cursorItem:{itemCount:0}});
          await new Promise(resolve=>setTimeout(resolve,250));
        }
        break;
      case 'menu':
        result={title:bot.currentWindow?.title,cursor:bot.currentWindow?.selectedItem?.name,
          slots:bot.currentWindow?.slots.slice(0,27).map(i=>i?{name:i.name,count:i.count,components:i.components}:null)};
        break;
      case 'menu_click':
        if(!bot.currentWindow) throw Error('No open menu');
        bot._client.write('window_click',{windowId:bot.currentWindow.id,stateId:-1,slot:request.slot,mouseButton:request.button||0,
          mode:request.mode||0,changedSlots:[],cursorItem:{itemCount:0}});
        await new Promise(resolve=>setTimeout(resolve,250));
        break;
      case 'close_menu':
        if(bot.currentWindow) bot.closeWindow(bot.currentWindow);
        break;
      case 'save': {
        const pixels=[...maps.values()].at(-1);
        if(!pixels) throw Error('No map texture');
        fs.writeFileSync(request.path,pixels);
        break;
      }
      case 'advertise':
        bot._client.write('custom_payload',{channel:'minecraft:register',data:Buffer.from('cobbleraids:radar_hologram_v1')});
        break;
      case 'save_hologram':
        if(!hologramScene?.heights) throw Error('No full hologram snapshot');
        fs.writeFileSync(request.path,JSON.stringify(hologramScene));
        break;
      case 'stats':
        result={packets,fullPackets,hologramFrames,hologramFullFrames,knownSamples,mapIds:[...maps.keys()],held:bot.heldItem?.name,
          orange:[...maps.values()].some(p=>p.includes(62))};
        break;
      default: throw Error('Unknown action');
    }
    emit({event:'result',id:request.id,ok:true,...result});
  } catch(err) {emit({event:'result',id:request?.id,ok:false,error:err.message});}
});
