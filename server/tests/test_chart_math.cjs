// Run with node; exercises the same interpolation and reference labels used by the page.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {smoothPath,readingStatus,pressureStatus}=require('../static/dashboard.js');
const fs=require('node:fs');
const path=require('node:path');

test('empty, isolated, and paired observations remain valid SVG paths',()=>{
  assert.equal(smoothPath([]),'');
  assert.equal(smoothPath([{x:3,y:7}]),'M3,7');
  assert.equal(smoothPath([{x:3,y:7},{x:9,y:11}]),'M3,7 L9,11');
});
test('duplicate timestamps keep both observations without invalid control points',()=>{
  const result=smoothPath([{x:10,y:120},{x:10,y:132},{x:20,y:80}]);
  assert.equal(result,'M10,120 L10,132 L20,80');
});
test('invalid coordinates are rejected',()=>assert.throws(()=>smoothPath([{x:NaN,y:80}]),TypeError));

function verifySegments(coords) {
  const tokens=smoothPath(coords).match(/[MLC]|-?\d+(?:\.\d+)?/g);
  assert.equal(tokens.shift(),'M');tokens.splice(0,2);
  for(let i=0;i<coords.length-1;i++){
    assert.equal(tokens.shift(),'C');
    const [x1,y1,x2,y2,x3,y3]=tokens.splice(0,6).map(Number),a=coords[i],b=coords[i+1];
    assert.equal(x3,b.x);assert.equal(y3,b.y);
    for(let j=0;j<=100;j++){
      const t=j/100,u=1-t,y=u*u*u*a.y+3*u*u*t*y1+3*u*t*t*y2+t*t*t*y3;
      const x=u*u*u*a.x+3*u*u*t*x1+3*u*t*t*x2+t*t*t*x3;
      assert(y>=Math.min(a.y,b.y)-.02 && y<=Math.max(a.y,b.y)+.02,'curve must not invent a peak or trough');
      assert(x>=a.x-.02 && x<=b.x+.02,'time must progress forward');
    }
  }
  assert.equal(tokens.length,0);
}
for(const [name,values] of [
  ['gradual change',[110,113,125,130,127]],
  ['alternating peaks',[260,50,200,70,160]],
  ['flat readings',[120,120,120,120,120]],
  ['flat section',[90,130,130,130,80]],
  ['irregular time gaps',[30,160,60,100,99]]
])test('smooth curve preserves readings: '+name,()=>verifySegments(values.map((y,i)=>({x:[0,1,20,21,200][i],y}))));

test('adult reference labels use the exact boundaries for both pressures',()=>{
  for(const [field,points] of [
    ['systolic',[[89,'low'],[90,'normal'],[119,'normal'],[120,'elevated'],[139,'elevated'],[140,'high']]],
    ['diastolic',[[59,'low'],[60,'normal'],[79,'normal'],[80,'elevated'],[89,'elevated'],[90,'high']]]
  ])for(const [value,expected] of points)assert.equal(readingStatus(value,field).kind,expected);
  assert.equal(readingStatus(null,'systolic').label,'');
  assert.equal(readingStatus(170,'pulse').kind,'neutral');
});
test('either abnormal pressure gets a label, without calling elevated readings normal',()=>{
  assert.equal(pressureStatus(110,70).label,'正常');
  assert.equal(pressureStatus(120,70).label,'正常高值');
  assert.equal(pressureStatus(110,90).label,'偏高');
  assert.equal(pressureStatus(88,65).label,'偏低');
  assert.equal(pressureStatus(140,58).label,'偏高');
  assert.equal(pressureStatus(null,null).label,'');
});
test('copy covers medical names and removes slogans and implementation commentary',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../static/index.html'),'utf8');
  const js=fs.readFileSync(path.join(__dirname,'../static/dashboard.js'),'utf8');
  assert.match(html,/收缩压/);assert.match(html,/舒张压/);assert.match(html,/历史明细/);
  assert.doesNotMatch(html+js,/看见变化|每一份关心|一份安心|关心，藏|THE BIG PICTURE|A DAY IN YOUR LIFE|EVERY READING MATTERS|FAMILY WELLNESS|IN FOCUS|按天呈现|缺测日不生成|横轴保留|从近到远排列/);
  const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
  assert.equal(ids.length,new Set(ids).size,'all HTML ids are unique');
  for(const [,id] of js.matchAll(/\$\("([a-z0-9-]+)"\)/g))assert(ids.includes(id),'missing element '+id);
});
