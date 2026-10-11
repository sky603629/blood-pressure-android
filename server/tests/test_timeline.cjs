const {test}=require('node:test');
const assert=require('node:assert/strict');
const {measurementTime,timelineDays}=require('../static/dashboard.js');
const reading=(date,id,offset=480,device='mom')=>({measuredAt:Date.parse(date),utcOffsetMinutes:offset,recordId:id,deviceId:device});

test('date and weekday follow the phone offset even across a year boundary',()=>{
  const east=measurementTime(reading('2025-12-31T16:30:00Z','a'));
  assert.equal(east.year,2026);assert.equal(east.key,'2026-01-01');
  assert.equal(east.date,'2026年1月1日');assert.equal(east.dayLabel,'1/1');
  assert.equal(east.weekday,'星期四');assert.equal(east.clock,'00:30');assert.equal(east.period,'night');
  const west=measurementTime(reading('2026-01-01T00:30:00Z','b',-300));
  assert.equal(west.key,'2025-12-31');assert.equal(west.clock,'19:30');assert.equal(west.period,'evening');
  assert.equal(west.dateTime,'2026-01-01T00:30:00.000Z');
});
test('time-period boundaries keep the full 24-hour clock',()=>{
  for(const [clock,period] of [['04:59','night'],['05:00','morning'],['11:59','morning'],['12:00','afternoon'],['17:59','afternoon'],['18:00','evening'],['23:59','evening']]) {
    const time=measurementTime(reading('2026-10-09T'+clock+':00+08:00',clock));
    assert.equal(time.clock,clock);assert.equal(time.period,period);
  }
});
test('same-day records from multiple people stay together with newest readings first',()=>{
  const records=[reading('2026-10-08T08:00:00+08:00','old'),reading('2026-10-09T08:00:00+08:00','morning'),
    reading('2026-10-09T20:00:00+08:00','mom-evening'),reading('2026-10-09T20:00:00+08:00','dad-evening',480,'dad')];
  const original=records.slice(),days=timelineDays(records);
  assert.deepEqual(days.map(d=>d.key),['2026-10-09','2026-10-08']);
  assert.deepEqual(days[0].entries.map(e=>e.record.recordId),['mom-evening','dad-evening','morning']);
  assert.equal(days.flatMap(d=>d.entries).length,records.length);
  assert.deepEqual(records,original,'rendering must not reorder the source records');
});
test('local midnight separates days, while mixed offsets cannot mislabel a card',()=>{
  const days=timelineDays([reading('2026-10-09T15:59:00Z','before'),reading('2026-10-09T16:00:00Z','after'),
    reading('2026-10-09T16:00:00Z','west',-300,'dad')]);
  assert.deepEqual(days.map(d=>d.key),['2026-10-10','2026-10-09']);
  for(const day of days)for(const entry of day.entries)assert.equal(entry.time.key,day.key);
  assert.deepEqual(days[0].entries.map(e=>e.record.recordId),['after']);
});
test('pagination does not drop, deduplicate, or merge individual measurements',()=>{
  const records=Array.from({length:25},(_,i)=>reading('2026-10-09T08:00:00+08:00',String(i)));
  const first=timelineDays(records.slice(0,20)),second=timelineDays(records.slice(20));
  assert.equal(first.length,1);assert.equal(first[0].entries.length,20);
  assert.equal(second.length,1);assert.equal(second[0].entries.length,5);
  assert.equal(new Set([...first,...second].flatMap(d=>d.entries.map(e=>e.record.recordId))).size,25);
  assert.deepEqual(timelineDays([]),[]);
});
test('invalid timestamps are rejected instead of displaying a false date',()=>{
  assert.throws(()=>measurementTime({measuredAt:NaN,utcOffsetMinutes:480}),TypeError);
  assert.throws(()=>measurementTime({measuredAt:0,utcOffsetMinutes:Infinity}),TypeError);
  assert.throws(()=>measurementTime({measuredAt:1e20,utcOffsetMinutes:480}),TypeError);
});
