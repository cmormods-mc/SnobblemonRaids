// Validate the same loading order GraalShowdownService will use: patched index.js loads raid-patch.js.
require('./showdown/index.js');
const {Battle} = require('./showdown/sim/battle');

const team = (name, species) => `${name}|${species}||100||0||static|tackle,growl,quickattack,tailwhip|35/35,40/40,30/30,15/15|hardy|0,0,0,0,0,0||31,31,31,31,31,31||50|255`;
const battle = new Battle({
  format:{name:'CobbleRaids Bootstrap Test', id:'cobbleraids-bootstrap-test', mod:'cobblemon', gameType:'raid', playerCount:3, ruleset:[]},
  formatid:'cobbleraids-bootstrap-test',
  send:()=>{}
});
battle.setPlayer('p1',{name:'Player 1',team:team('Pikachu','Pikachu')});
battle.setPlayer('p2',{name:'Player 2',team:team('Pikachu','Pikachu')});
battle.setPlayer('p3',{name:'Raid Boss',team:team('Charizard','Charizard')});
if (battle.sides.length !== 3) throw new Error(`expected 3 sides, got ${battle.sides.length}`);
if (battle.sides[2].foes(true).length !== 2) throw new Error('raid patch was not loaded by index.js');
const hp = battle.sides[2].pokemon[0].hp;
battle.choose('p1','move tackle 1');
battle.choose('p2','move tackle 1');
const raidDamageLines = battle.log.filter(x=>x.startsWith('|-raiddamage|p3'));
const raidDamage = raidDamageLines.length;
if (raidDamage !== 2) throw new Error(`expected 2 raid damage events, got ${raidDamage}`);
if (!raidDamageLines.some(x=>/\|p1[a-z]:/.test(x)) || !raidDamageLines.some(x=>/\|p2[a-z]:/.test(x))) throw new Error('explicit player damage sources missing');
if (battle.sides[2].pokemon[0].hp !== hp) throw new Error('boss simulator HP changed');
console.log(JSON.stringify({passed:true, sides:battle.sides.map(s=>s.id), raidDamage, bossSimulatorHP:hp}, null, 2));
