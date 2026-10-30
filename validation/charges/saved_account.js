const fs=require('fs'),zlib=require('zlib');
const nbt=require('../smoke/node_modules/prismarine-nbt');
(async()=>{
 const file=process.argv[2],wanted=process.argv[3];
 if(!fs.existsSync(file)){console.log(JSON.stringify({available:3,next_refill:0,default:true}));return;}
 const {parsed}=await nbt.parse(fs.readFileSync(file));
 const rows=parsed.value.data.value.players.value.value;
 const uuid=row=>row.uuid.value.map(v=>(v>>>0).toString(16).padStart(8,'0')).join('');
 const row=rows.find(row=>uuid(row)===wanted.replaceAll('-',''));
 if(!row){console.log(JSON.stringify({available:3,next_refill:0,default:true}));return;}
 if(process.argv[4]){
   const value=BigInt(Date.now())-BigInt(process.argv[4])*1000n;
   row.next_refill.value=[Number(BigInt.asIntN(32,value>>32n)),Number(BigInt.asIntN(32,value))];
   fs.writeFileSync(file,zlib.gzipSync(nbt.writeUncompressed(parsed,'big')));
 }
 const next=row.next_refill.value;
 console.log(JSON.stringify({available:row.available.value,next_refill:Number((BigInt(next[0])<<32n)+BigInt(next[1]>>>0))}));
})().catch(error=>{console.error(error);process.exit(1);});
