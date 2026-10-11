import {readFileSync,readdirSync} from 'node:fs';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const require=createRequire(resolve('tools/backend-tests/package.json'));
const {PGlite}=require('@electric-sql/pglite');
const db=new PGlite();
await db.exec(readFileSync('tools/backend-tests/fixture.sql','utf8'));
await db.exec(readFileSync('docs/challenges-migration.sql','utf8'));
for(const file of readdirSync('supabase/migrations').sort()) await db.exec(readFileSync(`supabase/migrations/${file}`,'utf8'));
assert.equal(readFileSync('supabase/proposals/202610110011_system_health.sql','utf8'),readFileSync('supabase/migrations/202610110011_system_health.sql','utf8'));
const A='10000000-0000-0000-0000-000000000001',B='10000000-0000-0000-0000-000000000002',ADMIN='10000000-0000-0000-0000-000000000003';
const adminEmail=readFileSync('supabase/migrations/202610100001_npc_admin_schema.sql','utf8').match(/lower\(u.email\)='([^']+)'/)[1];
await db.exec(`insert into auth.users(id,email,email_confirmed_at) values('${A}','a@example.test',now()),('${B}','b@example.test',now()),('${ADMIN}','${adminEmail}',now());
insert into public.profiles(id,display_name) values('${A}','A'),('${B}','B'),('${ADMIN}','Admin');
insert into public.user_roles values('${ADMIN}','ADMIN') on conflict(user_id) do update set role='ADMIN';`);
const q=async(sql,params=[]) => (await db.query(sql,params)).rows;
const as=async(user,sql,params=[])=>{
 await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub','${user}',false); select set_config('request.jwt.claim.role','authenticated',false)`);
 try{return await q(sql,params);}finally{await db.exec(`reset role; select set_config('request.jwt.claim.sub','',false)`);}
};
const rpc=async(user,name,args=[]) => (await as(user,`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) result`,args))[0].result;
let serial=1,passed=0;
const report=(overrides={})=>({id:`20000000-0000-0000-0000-${String(serial++).padStart(12,'0')}`,installation_id:A,
 occurred_at:new Date().toISOString(),category:'GPS',code:'GPS_SIGNAL_LOST',feature:'TRAINING',severity:'WARNING',
 version_name:'1.0.6',version_code:7,android_api:35,device_model:'Pixel 8',foreground:false,training:true,
 frames:['se.oxelv.mintracker.tracking.TrackingService#requestUpdates'],occurrences:1,...overrides});
const submit=(user,items)=>rpc(user,'submit_app_error_reports',[JSON.stringify(items)]);
const test=async(name,fn)=>{await fn();passed++;console.log('PASS',name);};
let original,fp;
await test('authenticated ingestion assigns owner and accepts stable UUID',async()=>{
 original=report();assert.deepEqual((await submit(A,[original])).accepted_ids,[original.id]);
 const rows=await q('select * from public.app_error_reports');assert.equal(rows[0].user_id,A);fp=rows[0].fingerprint;
});
await test('retry is idempotent and does not inflate occurrences',async()=>{
 await submit(A,[original]);assert.equal((await q('select count(*)::int n from public.app_error_reports'))[0].n,1);
 assert.equal(Number((await q('select occurrences from public.app_error_groups'))[0].occurrences),1);
});
await test('ordinary users cannot read even their own technical reports or groups',async()=>{
 assert.equal((await as(A,'select * from public.app_error_reports')).length,0);
 assert.equal((await as(B,'select * from public.app_error_groups')).length,0);
 await assert.rejects(rpc(A,'admin_system_health'));await assert.rejects(rpc(A,'admin_system_health_detail',[fp]));
 await assert.rejects(rpc(A,'admin_system_health_status',[fp,'RESOLVED']));
});
await test('direct insertion and anonymous submission are forbidden',async()=>{
 await assert.rejects(as(A,`insert into public.app_error_groups(fingerprint,category,code,feature,severity) values('${'a'.repeat(32)}','GPS','X','TRAINING','ERROR')`));
 await db.exec('set role anon');try{await assert.rejects(q('select public.submit_app_error_reports($1)',[JSON.stringify([report()])]))}finally{await db.exec('reset role')}
});
await test('cross-account UUID collision cannot acknowledge another account report',async()=>{await assert.rejects(submit(B,[original]));});
await test('identical errors group across app versions and count affected users',async()=>{
 await submit(B,[report({version_name:'1.0.7',version_code:8,occurrences:3})]);
 const health=await rpc(ADMIN,'admin_system_health');assert.equal(health.groups.length,1);
 assert.equal(health.errors_24h,4);assert.equal(health.affected_users_24h,2);assert.equal(health.by_version.length,2);
 const detail=await rpc(ADMIN,'admin_system_health_detail',[fp]);assert.equal(detail.reports.length,2);assert.equal(detail.versions.length,2);
});
await test('admin status updates are audited and invalid status is rejected',async()=>{
 await rpc(ADMIN,'admin_system_health_status',[fp,'INVESTIGATING']);
 assert.equal((await rpc(ADMIN,'admin_system_health')).groups[0].status,'INVESTIGATING');
 assert.equal((await q("select * from public.admin_audit_log where action='SYSTEM_HEALTH_STATUS'")).length,1);
 await assert.rejects(rpc(ADMIN,'admin_system_health_status',[fp,'CUSTOM']));
});
await test('free messages, coordinates, URLs, raw exception text and emails are rejected',async()=>{
 for(const extra of [{message:'secret'}, {latitude:59}, {frames:['https://host/path?token=secret']}, {frames:['user@example.test']},
   {frames:['java.lang.Exception: password=secret']},{device_model:'user@example.test'},{version_name:'https://host'}])
   await assert.rejects(submit(A,[report(extra)]));
});
await test('invalid classifications, timestamps, sizes and unknown fields are rejected',async()=>{
 for(const extra of [{code:'SECRET_TOKEN'},{category:'BOGUS'},{severity:'BOGUS'},{feature:'SECRET'},
   {occurred_at:'2000-01-01T00:00:00Z'},{frames:Array(21).fill('se.oxelv.mintracker.Test#test')},{frames:[null]},
   {code:'COUNTDOWN_STARTED',severity:'CRITICAL'},{occurrences:0},{occurrences:1001},{version_code:-1},{android_api:2},{user_id:B}])
   await assert.rejects(submit(A,[report(extra)]));
 await assert.rejects(submit(A,Array.from({length:21},()=>report())));
});
await test('a rejected batch rolls back all reports and group counts',async()=>{
 const before=await q('select count(*)::int n from public.app_error_reports');
 await assert.rejects(submit(A,[report(),report({frames:['unsafe']})]));
 assert.deepEqual(await q('select count(*)::int n from public.app_error_reports'),before);
});
await test('diagnostic INFO events do not inflate error/crash health metrics',async()=>{
 await submit(A,[report({code:'COUNTDOWN_STARTED',severity:'INFO'})]);
 const health=await rpc(ADMIN,'admin_system_health');assert.equal(health.errors_24h,4);assert.equal(health.crashes_24h,0);
});
await test('verified crash and suspected process interruption remain distinguishable',async()=>{
 await submit(A,[report({category:'CRASH',code:'PROCESS_CRASH',severity:'CRITICAL'}),report({code:'PROCESS_INTERRUPTED',severity:'WARNING'})]);
 const health=await rpc(ADMIN,'admin_system_health');assert.equal(health.crashes_24h,1);assert.equal(health.critical_24h,1);
});
await test('filtering and code search return matching groups only',async()=>{
 assert.equal((await rpc(ADMIN,'admin_system_health',[0,'PROCESS_CRASH','CRASH','CRITICAL','ALL'])).groups.length,1);
 assert.equal((await rpc(ADMIN,'admin_system_health',[0,'', 'ALL','ALL','INVESTIGATING'])).groups.length,1);
});
await test('rate limiting bounds new reports while replay acknowledgements still work',async()=>{
 await q("update public.app_error_reports set received_at=now()-interval '2 minutes'");
 await submit(B,Array.from({length:20},()=>report()));
 await assert.rejects(submit(B,[report()]));await submit(A,[original]);
});
await test('deleting an account removes its raw reports while preserving other accounts',async()=>{
 assert.ok((await q('select * from public.app_error_reports where user_id=$1',[B])).length>0);
 await q('delete from auth.users where id=$1',[B]);
 assert.equal((await q('select * from public.app_error_reports where user_id=$1',[B])).length,0);
 assert.ok((await q('select * from public.app_error_reports where user_id=$1',[A])).length>0);
});
await test('retention removes raw reports after 30 days and groups after 90 days',async()=>{
 await q("update public.app_error_reports set received_at=now()-interval '31 days'");
 await q("update public.app_error_groups set last_seen=now()-interval '91 days'");
 await q('select public.purge_app_diagnostics()');
 assert.equal((await q('select * from public.app_error_reports')).length,0);assert.equal((await q('select * from public.app_error_groups')).length,0);
});
console.log(`PASS ${passed} system-health proposal checks (local PostgreSQL only)`);
await db.close();
