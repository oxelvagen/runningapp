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
// 010 is approved and installed; load its canonical migration with the others.
const A='10000000-0000-0000-0000-000000000001',B='10000000-0000-0000-0000-000000000002',C='10000000-0000-0000-0000-000000000003';
const q=async(sql,params=[]) => (await db.query(sql,params)).rows;
const as=async(user,sql,params=[])=>{
 await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub','${user}',false); select set_config('request.jwt.claim.role','authenticated',false)`);
 try{return await q(sql,params);}finally{await db.exec(`reset role; select set_config('request.jwt.claim.sub','',false)`);}
};
const rpc=async(user,name,args=[]) => (await as(user,`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) result`,args))[0].result;
const page=(user=B,...args)=>rpc(user,'get_notification_center',args);
const count=(user=B)=>rpc(user,'get_notification_unread_count');
let passed=0,serial=10;
const test=async(name,fn)=>{try{await fn();passed++;console.log('PASS',name);}catch(e){throw new Error(`FAIL ${name}: ${e.message}`,{cause:e});}};
await db.exec(`insert into auth.users(id,email,email_confirmed_at) values('${A}','a@example.test',now()),('${B}','b@example.test',now()),('${C}','c@example.test',now());
insert into public.profiles(id,display_name,is_public) values('${A}','Andreas',true),('${B}','Sara',true),('${C}','Christian',true);
insert into public.friendships(user_low_id,user_high_id) values('${A}','${B}');`);
const activity=(meters)=>({id:`20000000-0000-0000-0000-${String(serial++).padStart(12,'0')}`,user_id:A,activity_type:'RUN',
 started_at:new Date(Date.now()-7200000).toISOString(),ended_at:new Date(Date.now()-3600000).toISOString(),duration_seconds:1800,
 distance_meters:meters,note:'PRIVATE NOTE NEVER RETURNED',dog_id:null});
const sync=(a,visibility='PRIVATE')=>rpc(A,'sync_social_activity',[a,visibility,false]);
let privatePass,shared,event,nid,challenge,group;
await test('private pass has no friend notification and old unsafe generator is removed',async()=>{
 privatePass=activity(1000);await sync(privatePass);
 assert.equal((await page()).items.length,0);assert.equal(Number(await count()),0);
 assert.equal((await q('select * from public.notifications where activity_id=$1',[privatePass.id])).length,0);
});
await test('shared activity and actual record produce one notice each, excluding self and nonfriends',async()=>{
 shared=activity(5000);shared.started_at=new Date(Date.now()-6000000).toISOString();await sync(shared,'FRIENDS');
 const items=(await page()).items;assert.equal(items.filter(x=>x.event_type==='ACTIVITY_COMPLETED').length,1);
 assert.equal(items.filter(x=>x.event_type==='PERSONAL_RECORD').length,1);assert.equal((await page(A)).items.length,0);assert.equal((await page(C)).items.length,0);
 event=items.find(x=>x.event_type==='ACTIVITY_COMPLETED').payload.social_event_id;nid=items.find(x=>x.event_type==='ACTIVITY_COMPLETED').id;
});
await test('retrying sync preserves IDs, unread count and lack of duplicates',async()=>{
 const before=await page();await sync(shared,'PRIVATE');await sync(shared,'FRIENDS');assert.deepEqual(await page(),before);
});
await test('allowlisted payload and visitor activity detail contain no GPS, notes or precise pass times',async()=>{
 const payload=JSON.stringify([await page(),await rpc(B,'get_notification_activity',[shared.id])]);
 for(const key of ['PRIVATE NOTE','latitude','longitude','started_at','ended_at','refresh_token','@example.test']) assert.ok(!payload.includes(key));
 await assert.rejects(rpc(C,'get_notification_activity',[shared.id]));
});
await test('read is idempotent; unread tab and badge reflect the same visible records',async()=>{
 const before=Number(await count());const result=await rpc(B,'read_notification',[nid]);assert.equal(result.available,true);assert.equal(Number(result.unread_count),before-1);
 await rpc(B,'read_notification',[nid]);assert.equal(Number(await count()),before-1);
 assert.ok(!(await page(B,true)).items.some(x=>x.id===nid));assert.ok((await page()).items.find(x=>x.id===nid).read_at);
});
await test('another account cannot read or mark my notification and cannot forge raw data',async()=>{
 assert.equal((await rpc(C,'read_notification',[nid])).available,false);
 for(const table of ['notification_receipts','notification_preferences']) await assert.rejects(as(C,`select * from public.${table}`));
 await assert.rejects(as(C,`insert into public.notification_receipts(recipient_id,notification_id) values($1,$2)`,[B,nid]));
 await assert.rejects(as(C,`select * from public._notification_candidates($1)`,[B]));
 await assert.rejects(as(C,`insert into public.notifications(user_id,type,title,body) values($1,'SYSTEM_MESSAGE','Fake','Fake')`,[B]));
});
await test('cheer notification targets the owner, is idempotent and vanishes when removed',async()=>{
 await rpc(B,'set_social_cheer',[event,true]);await rpc(B,'set_social_cheer',[event,true]);
 assert.equal((await page(A)).items.filter(x=>x.event_type==='CHEER').length,1);
 await rpc(B,'set_social_cheer',[event,false]);assert.equal((await page(A)).items.filter(x=>x.event_type==='CHEER').length,0);
});
await test('preferences belong to the account; categories can be disabled and stale writes fail',async()=>{
 const pref=await rpc(B,'get_notification_preferences');await rpc(B,'set_notification_preferences',[{...pref.categories,records:false},pref.revision]);
 assert.ok(!(await page()).items.some(x=>x.event_type==='PERSONAL_RECORD'));
 assert.equal((await rpc(A,'get_notification_preferences')).categories.records,true);
 await assert.rejects(rpc(B,'set_notification_preferences',[pref.categories,pref.revision]));
 await assert.rejects(rpc(B,'set_notification_preferences',[{activities:'yes'},1]));
 const latest=await rpc(B,'get_notification_preferences');await rpc(B,'set_notification_preferences',[pref.categories,latest.revision]);
});
await test('private edit removes notices, details and unread count immediately',async()=>{
 await rpc(A,'set_activity_sharing',[shared.id,'PRIVATE',false,0]);assert.equal((await page()).items.length,0);assert.equal(Number(await count()),0);
 assert.equal((await rpc(B,'read_notification',[nid])).available,false);await assert.rejects(rpc(B,'get_notification_activity',[shared.id]));
 await rpc(A,'set_activity_sharing',[shared.id,'FRIENDS',false,1]);assert.ok((await page()).items.some(x=>x.id===nid));
});
await test('friend removal revokes both new and old inboxes immediately',async()=>{
 const old=(await q(`insert into public.notifications(user_id,type,title,body,actor_profile_id,activity_id) values($1,'FRIEND_ACTIVITY','old','old',$2,$3) returning id`,[B,A,shared.id]))[0].id;
 assert.equal((await as(B,'select * from public.notifications where id=$1',[old])).length,1);
 await db.exec(`delete from public.friendships where user_low_id='${A}' and user_high_id='${B}'`);
 assert.equal((await page()).items.length,0);assert.equal(Number(await count()),0);assert.equal((await as(B,'select * from public.notifications where id=$1',[old])).length,0);
 await db.exec(`insert into public.friendships(user_low_id,user_high_id) values('${A}','${B}')`);
});
await test('friend request and accepted request reflect current relationship',async()=>{
 const f=(await q('insert into public.friend_requests(sender_id,receiver_id) values($1,$2) returning id',[C,B]))[0].id;
 assert.equal((await page()).items.filter(x=>x.event_type==='FRIEND_REQUEST').length,1);
 await q(`update public.friend_requests set status='ACCEPTED',responded_at=now() where id=$1`,[f]);
 await q('insert into public.friendships(user_low_id,user_high_id) values($1,$2)',[B,C]);
 assert.equal((await page()).items.filter(x=>x.event_type==='FRIEND_REQUEST').length,0);
 assert.equal((await page(C)).items.filter(x=>x.event_type==='FRIEND_ACCEPTED').length,1);
});
await test('new challenge respects PRIVATE, invitation and group access; deletion removes target',async()=>{
 challenge=(await q(`insert into public.challenges(creator_id,title,metric_type,target_value,scope,time_zone,start_at,end_at) values($1,'October','DISTANCE',5000,'PRIVATE','UTC',now()-interval '1 day',now()+interval '1 day') returning id`,[A]))[0].id;
 assert.ok(!(await page()).items.some(x=>x.target_id===challenge));
 await q(`update public.challenges set scope='FRIENDS' where id=$1`,[challenge]);
 assert.ok(!(await page()).items.some(x=>x.target_id===challenge));
 await q('insert into public.challenge_invitations(challenge_id,sender_id,receiver_id) values($1,$2,$3)',[challenge,A,B]);
 assert.equal((await page()).items.filter(x=>x.event_type==='CHALLENGE_CREATED'&&x.target_id===challenge).length,1);
 assert.ok(!(await page(C)).items.some(x=>x.target_id===challenge));
 await q('delete from public.challenges where id=$1',[challenge]);assert.ok(!(await page()).items.some(x=>x.target_id===challenge));
});
await test('group notices go to the new member and owner, not every member; leaving revokes',async()=>{
 group=(await q('insert into public.groups(name,owner_id) values($1,$2) returning id',['Runners',A]))[0].id;
 await q(`insert into public.group_members(group_id,user_id,role) values($1,$2,'OWNER'),($1,$3,'MEMBER'),($1,$4,'MEMBER')`,[group,A,B,C]);
 assert.equal((await page(A)).items.filter(x=>x.event_type==='GROUP_MEMBER_JOINED').length,2);
 assert.equal((await page()).items.filter(x=>x.event_type==='GROUP_JOINED').length,1);
 assert.equal((await page()).items.filter(x=>x.event_type==='GROUP_MEMBER_JOINED').length,0);
 await q('delete from public.group_members where group_id=$1 and user_id=$2',[group,B]);assert.ok(!(await page()).items.some(x=>x.target_id===group));
});
await test('system messages survive disabling every optional category and legacy read sync works',async()=>{
 const n=(await q(`insert into public.notifications(user_id,type,title,body) values($1,'SYSTEM_MESSAGE','Maintenance','Hello') returning id`,[B]))[0].id;
 const pref=await rpc(B,'get_notification_preferences');await rpc(B,'set_notification_preferences',[Object.fromEntries(Object.keys(pref.categories).map(k=>[k,false])),pref.revision]);
 const result=await page();assert.equal(result.items.length,1);assert.equal(result.items[0].event_type,'SYSTEM_MESSAGE');
 await rpc(B,'read_notification',[result.items[0].id]);assert.equal((await as(B,'select is_read from public.notifications where id=$1',[n]))[0].is_read,true);
 const latest=await rpc(B,'get_notification_preferences');await rpc(B,'set_notification_preferences',[pref.categories,latest.revision]);
});
await test('mark all returns zero and does not mark another account read',async()=>{
 const before=await page(A);await rpc(B,'read_all_notifications');assert.equal(Number(await count()),0);assert.deepEqual(await page(A),before);
});
await test('cursor pagination has no missing or repeated IDs; invalid cursors denied',async()=>{
 let cursor=null;const seen=new Set();do{const result=await page(B,false,cursor?.created_at??null,cursor?.id??null,1);
 for(const n of result.items){assert.ok(!seen.has(n.id));seen.add(n.id);}cursor=result.next_cursor;}while(cursor);
 assert.equal(seen.size,(await page()).items.length);await assert.rejects(page(B,false,null,null,1000));await assert.rejects(page(B,false,new Date().toISOString(),null,20));
});
await test('NPC is explicitly labelled and shares under identical friend/privacy rules',async()=>{
 await q(`update public.profiles set account_type='NPC' where id=$1`,[A]);await q('insert into public.npc_profiles(profile_id,seed) values($1,17)',[A]);
 const item=(await page()).items.find(x=>x.event_type==='ACTIVITY_COMPLETED');assert.equal(item.payload.is_npc,true);
 assert.ok(!(await page(C)).items.some(x=>x.event_type==='ACTIVITY_COMPLETED'));
 await q('update public.npc_profiles set notifications_enabled=false where profile_id=$1',[A]);assert.ok(!(await page()).items.some(x=>x.actor_id===A));
 await q('update public.npc_profiles set notifications_enabled=true where profile_id=$1',[A]);
 await q(`update public.profiles set account_type='USER' where id=$1`,[A]);
});
await test('deleted activity and account cannot be opened and drop visible notices',async()=>{
 await as(A,'delete from public.activities where id=$1',[shared.id]);assert.ok(!(await page()).items.some(x=>x.target_id===shared.id));
 await assert.rejects(rpc(B,'get_notification_activity',[shared.id]));await q('delete from auth.users where id=$1',[A]);assert.ok(!(await page()).items.some(x=>x.actor_id===A));
});
await test('anonymous caller has no notification RPC or table access',async()=>{
 await db.exec('set role anon');try{await assert.rejects(q('select public.get_notification_center()'));await assert.rejects(q('select * from public.notification_receipts'));}finally{await db.exec('reset role');}
});
console.log(`PASS ${passed} notification proposal groups. Local database only; linked Supabase unchanged.`);
await db.close();
