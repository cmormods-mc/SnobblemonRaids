require('./showdown/raid-patch');
const {Battle} = require('./showdown/sim/battle');
const team = (name, species) => `${name}|${species}||100||0||static|tackle,growl,quickattack,tailwhip|35/35,40/40,30/30,15/15|hardy|0,0,0,0,0,0||31,31,31,31,31,31||50|255`;

function test(playerCount) {
  const totalSides = playerCount + 1;
  const chunks=[];
  const battle = new Battle({
    format:{name:`CobbleRaids ${playerCount}P Test`, id:`cobbleraids-${playerCount}p-test`, mod:'cobblemon', gameType:'raid', playerCount:totalSides, ruleset:[]},
    formatid:`cobbleraids-${playerCount}p-test`,
    send:(type,data)=>chunks.push([type,Array.isArray(data)?data.join('\n'):data])
  });
  for (let i=1;i<=playerCount;i++) battle.setPlayer('p'+i,{name:'Player '+i,team:team('Pikachu','Pikachu')});
  const bossSlot = 'p' + totalSides;
  battle.setPlayer(bossSlot,{name:'Raid Boss',team:team('Charizard','Charizard')});

  if (battle.sides.length !== totalSides) throw new Error(`${playerCount}P: expected ${totalSides} sides, got ${battle.sides.length}`);
  const boss = battle.sides[totalSides - 1];
  if (!boss || boss.foes(true).length !== playerCount) throw new Error(`${playerCount}P: boss foe topology failed`);
  for (let i=0;i<playerCount;i++) {
    const player = battle.sides[i];
    if (player.foes(true).length !== 1 || player.foes(true)[0].side !== boss) throw new Error(`${playerCount}P: player ${i+1} foe topology failed`);
  }
  if (!battle.started || battle.requestState !== 'move') throw new Error(`${playerCount}P: battle did not start with move requests`);

  const initialBossHP = boss.pokemon[0].hp;
  for (let i=1;i<=playerCount;i++) battle.choose('p'+i,'move tackle 1');
  const raidDamageLines = battle.log.filter(x=>x.startsWith(`|-raiddamage|${bossSlot}`));
  const raidDamage = raidDamageLines.length;
  if (raidDamage !== playerCount) throw new Error(`${playerCount}P: expected ${playerCount} raid damage events, got ${raidDamage}`);
  for (let i=1;i<=playerCount;i++) {
    if (!raidDamageLines.some(line => new RegExp(`\\|p${i}[a-z]:`).test(line))) throw new Error(`${playerCount}P: missing explicit source for player p${i}`);
  }
  if (boss.pokemon[0].hp !== initialBossHP) throw new Error(`${playerCount}P: boss simulator HP mutated`);

  return {
    playerCount,
    sideCount:battle.sides.length,
    sideIds:battle.sides.map(s=>s.id),
    bossFoes:boss.foes(true).length,
    raidDamage,
    sources: raidDamageLines.map(line => line.split('|').at(-1).trim()),
    bossSimulatorHP:boss.pokemon[0].hp,
    initialBossHP
  };
}

const results=[];
for (let players=1; players<=4; players++) results.push(test(players));
console.log(JSON.stringify({passed:true, results}, null, 2));
