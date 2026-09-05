require('./showdown/raid-patch');
const {BattleStream} = require('./showdown/sim/battle-stream');

const team = (name, species) => `${name}|${species}||100||0||static|tackle,growl,quickattack,tailwhip|35/35,40/40,30/30,15/15|hardy|0,0,0,0,0,0||31,31,31,31,31,31||50|255`;
const stream = new BattleStream({keepAlive:true});
const winners = ['11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333'];
stream._writeLines(`>start ${JSON.stringify({format:{name:'Raid Victory Test',id:'raid-victory-test',mod:'cobblemon',gameType:'raid',playerCount:4,ruleset:[]},formatid:'raid-victory-test'})}`);
for (let i=0;i<winners.length;i++) stream._writeLines(`>player p${i+1} ${JSON.stringify({name:winners[i],team:team('Pikachu','Pikachu')})}`);
stream._writeLines(`>player p4 ${JSON.stringify({name:'boss-uuid',team:team('Charizard','Charizard')})}`);
if (!stream.battle || !stream.battle.started) throw new Error('raid did not start');
stream._writeLines(`>raidwin ${winners.join('&')}`);
const expected = `|win|${winners.join('&')}`;
if (!stream.battle.ended) throw new Error('raidwin did not end the simulator battle');
if (!stream.battle.log.includes(expected)) throw new Error(`missing normal win protocol: ${expected}\n${stream.battle.log.join('\n')}`);
console.log(JSON.stringify({passed:true,ended:stream.battle.ended,winner:stream.battle.winner,protocol:expected}, null, 2));
