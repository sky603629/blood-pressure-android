"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const ns = "http://www.w3.org/2000/svg";
  const colors = {systolic:"#bb6551",diastolic:"#567f9b",pulse:"#a48234"};
  const palette = ["#478574","#ae8060","#7286a8","#ac7995","#93954c","#648c97"];
  const names = {morning:"上午",afternoon:"下午",evening:"晚上",night:"凌晨"};
  const sources = {camera:"拍照录入",manual:"手动录入",import:"导入记录",legacy:"历史记录"};
  const pageSize = 20;
  const preferenceKey = "pressure-diary.display.v1";
  const defaultPreferences = () => ({memberIds:null,showAll:true});
  function readPreferences() {
    try {
      const value=JSON.parse(localStorage.getItem(preferenceKey));
      if(!value || value.version!==1 || typeof value.showAll!=="boolean")return defaultPreferences();
      if(value.memberIds!==null && (!Array.isArray(value.memberIds) || !value.memberIds.length || value.memberIds.length>100 ||
          !value.memberIds.every(id=>typeof id==="string" && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id))))return defaultPreferences();
      return {memberIds:value.memberIds===null?null:[...new Set(value.memberIds)],showAll:value.showAll};
    } catch {return defaultPreferences();}
  }
  let displayPreferences=readPreferences();
  let token="", busy=false, offset=0, total=0, devices=[], overview=null, rows=[], raw=[], rawTotal=0;
  let mode="daily", points=[], selected=-1, positions=[], cursor=null, applied=null, range="30";
  let graphX = null, graphY = null;
  function node(tag,cls,text="") { const e=document.createElement(tag);e.className=cls;e.textContent=text;return e; }
  function svg(tag,attrs={}) { const e=document.createElementNS(ns,tag);for(const [k,v] of Object.entries(attrs))e.setAttribute(k,String(v));return e; }
  function icon(name) { const e=svg("svg",{class:"icon","aria-hidden":"true"});e.append(svg("use",{href:"#i-"+name}));return e; }
  function number(value,decimals=0) { return value==null || !Number.isFinite(Number(value)) ? "—" : Number(value).toFixed(decimals).replace(/\.0$/,""); }
  const pad=n=>String(n).padStart(2,"0");
  function inputDate(date) { return date.getFullYear()+"-"+pad(date.getMonth()+1)+"-"+pad(date.getDate()); }
  function localTime(row) {
    const d=new Date(row.measuredAt+row.utcOffsetMinutes*60000),h=d.getUTCHours();
    const period=h<5?"night":h<12?"morning":h<18?"afternoon":"evening";
    return {date:d.getUTCFullYear()+"年"+(d.getUTCMonth()+1)+"月"+d.getUTCDate()+"日",clock:pad(h)+":"+pad(d.getUTCMinutes()),period};
  }
  function message(text="") { $("message").textContent=text;$("message").hidden=!text; }
  function setBusy(value) {
    busy=value;
    $("records-panel").setAttribute("aria-busy",String(value));
    document.querySelectorAll("button,input,select").forEach(e=>e.disabled=value);
    if(!value) {
      $("previous").disabled=offset===0;
      $("next").disabled=offset+pageSize>=total;
      $("point-previous").disabled=selected<=0;
      $("point-next").disabled=selected<0 || selected>=points.length-1;
      $("export").disabled=!overview || overview.summary.count===0;
    }
    $("connect").textContent=value && !$("login").hidden ? "正在打开…" : "进入看板 →";
  }
  async function request(path) {
    const response=await fetch(path,{headers:{Authorization:"Bearer "+token},cache:"no-store",redirect:"error"});
    if(!response.ok) {
      const error=new Error(response.status===401?"查看密钥不正确或已被替换，请重新填写。":"暂时无法读取服务器数据，请稍后重试。");
      error.status=response.status;throw error;
    }
    return response;
  }
  function params() {
    const p=new URLSearchParams(),start=$("start").value,end=$("end").value;
    if(start && end && start>end) throw new Error("开始日期不能晚于结束日期。");
    if($("member").value)p.set("device_id",$("member").value);
    else if(displayPreferences.memberIds!==null)visibleDevices().forEach(d=>p.append("device_ids",d.id));
    if(start)p.set("from_ms",new Date(start+"T00:00:00").getTime());
    if(end){const date=new Date(end+"T00:00:00");date.setDate(date.getDate()+1);p.set("to_ms",date.getTime());}
    return p;
  }
  function setRange(value) {
    range=value;
    const end=new Date(),start=new Date(end);
    if(value==="all"){$("start").value="";$("end").value="";}
    else{start.setDate(start.getDate()-Number(value)+1);$("start").value=inputDate(start);$("end").value=inputDate(end);}
    updateRange();
  }
  function updateRange() { document.querySelectorAll("[data-range]").forEach(b=>{const active=b.dataset.range===range;b.classList.toggle("selected",active);b.setAttribute("aria-pressed",String(active));}); }
  function restoreFilters() {
    if(!applied)return;
    $("start").value=applied.start;$("end").value=applied.end;$("member").value=applied.member;range=applied.range;updateRange();renderMembers();
  }
  async function action(fn) {
    if(busy)return;message();setBusy(true);
    try{await fn();}
    catch(error){if(error.status===401)logout();else restoreFilters();message(error.message||"网络连接失败，请稍后重试。");}
    finally{setBusy(false);}
  }
  async function loadMembers(initial=false) {
    const result=await(await request("/api/v1/devices")).json();devices=result.devices;
    if(devices.length && !visibleDevices().length)displayPreferences=defaultPreferences();
    rebuildMemberOptions(initial);
    renderMembers();
  }
  function visibleDevices() {return devices.filter(d=>displayPreferences.memberIds===null || displayPreferences.memberIds.includes(d.id));}
  function hasAllMembers() {return displayPreferences.showAll && visibleDevices().length>1;}
  function allMemberName() {return visibleDevices().length<devices.length?"全部已显示家人":"全部家人";}
  function rebuildMemberOptions(initial=false) {
    const previous=$("member").value,visible=visibleDevices(),allowAll=hasAllMembers();
    $("member").replaceChildren();
    if(allowAll)$("member").add(new Option(allMemberName(),""));
    visible.forEach(d=>$("member").add(new Option(d.name,d.id)));
    const fallback=visible.slice().sort((a,b)=>b.recordCount-a.recordCount)[0]?.id||"";
    $("member").value=!initial && (visible.some(d=>d.id===previous) || previous==="" && allowAll)?previous:fallback;
  }
  function renderMembers() {
    const visible=visibleDevices(),list=$("member-list");list.replaceChildren();$("member-total").textContent=visible.length;
    const choices=hasAllMembers()?[{id:"",name:allMemberName(),recordCount:visible.reduce((s,d)=>s+d.recordCount,0),enabled:1},...visible]:visible;
    for(const d of choices) {
      const b=node("button","member-button");b.type="button";b.dataset.member=d.id;b.title=d.name;
      const active=$("member").value===d.id;b.classList.toggle("selected",active);b.setAttribute("aria-pressed",String(active));
      const avatar=node("span","avatar",d.id?Array.from(d.name).slice(0,2).join(""):"全");
      const copy=node("span","member-copy");copy.append(node("strong","",d.name),node("small","",d.enabled?"全部记录":"上传已停用"));
      b.append(avatar,copy,node("span","member-count",d.recordCount));
      b.addEventListener("click",()=>action(async()=>{$("member").value=d.id;await loadAll();}));list.append(b);
    }
  }
  function writePreferences() {
    try{localStorage.setItem(preferenceKey,JSON.stringify({version:1,...displayPreferences}));return true;}catch{return false;}
  }
  function openSettings() {
    const list=$("settings-members");list.replaceChildren();$("settings-error").textContent="";
    devices.forEach(d=>{
      const label=node("label","settings-member"),input=document.createElement("input");
      input.type="checkbox";input.value=d.id;input.name="visibleMember";input.checked=displayPreferences.memberIds===null || displayPreferences.memberIds.includes(d.id);
      const copy=node("span","settings-member-copy");copy.append(node("strong","",d.name),node("small","",d.recordCount+" 条记录"+(d.enabled?"":" · 上传已停用")));
      label.append(input,copy);list.append(label);
    });
    $("settings-show-all").checked=displayPreferences.showAll;$("save-settings").disabled=!devices.length;
    $("settings-dialog").showModal();
  }
  $("settings").addEventListener("click",()=>{if(!busy)openSettings();});
  $("close-settings").addEventListener("click",()=>$("settings-dialog").close());
  $("settings-dialog").addEventListener("click",event=>{if(!busy && event.target===$("settings-dialog"))$("settings-dialog").close();});
  $("settings-reset").addEventListener("click",()=>{
    $("settings-members").querySelectorAll("input").forEach(input=>input.checked=true);$("settings-show-all").checked=true;$("settings-error").textContent="";
  });
  $("settings-form").addEventListener("submit",event=>{
    event.preventDefault();const ids=[...$("settings-members").querySelectorAll("input:checked")].map(input=>input.value);
    if(!ids.length){$("settings-error").textContent="请至少选择一位家人。";return;}
    action(async()=>{
      const previous=displayPreferences;displayPreferences={memberIds:ids.length===devices.length?null:ids,showAll:$("settings-show-all").checked};
      $("settings-error").textContent="";rebuildMemberOptions();
      try{await loadAll();}catch(error){displayPreferences=previous;rebuildMemberOptions();$("settings-error").textContent="设置尚未保存，请检查连接后重试。";throw error;}
      const saved=writePreferences();$("settings-dialog").close();
      if(!saved)message("已应用显示设置；当前浏览器不允许保存，刷新后需重新设置。");
    });
  });
  async function loadAll() {
    const p=params(),listParams=new URLSearchParams(p);listParams.set("limit",pageSize);listParams.set("offset",0);
    const [data,page]=await Promise.all([request("/api/v1/overview?"+p).then(r=>r.json()),request("/api/v1/measurements?"+listParams).then(r=>r.json())]);
    let individual=[];
    if(mode==="reading"){const q=new URLSearchParams(p);q.set("limit",200);individual=await(await request("/api/v1/measurements?"+q)).json();}
    overview=data;rows=page.records;total=page.total;offset=0;
    raw=individual.records||[];rawTotal=individual.total||0;
    applied={query:p.toString(),member:$("member").value,start:$("start").value,end:$("end").value,range};
    renderMembers();renderSummary();renderPeriods();renderRows();renderChart();
    $("updated-at").textContent="更新于 "+new Date().toLocaleTimeString("zh-CN",{hour:"2-digit",minute:"2-digit"});
  }
  function renderSummary() {
    const s=overview.summary,r=overview.latest,multiple=!applied.member && s.members>1;
    const member=devices.find(d=>d.id===applied.member);
    $("page-title").textContent=member?member.name+"的健康概览":"家人的健康概览";
    const period=applied.start && applied.end ? applied.start+" — "+applied.end : "全部测量记录";
    $("page-subtitle").textContent=period+" · "+s.count+" 次测量 · "+s.days+" 个记录日";
    $("latest-pressure").replaceChildren(document.createTextNode(r?String(r.systolic):"—"),node("span","","/"),document.createTextNode(r?String(r.diastolic):"—"),node("small","","mmHg"));
    $("latest-member").textContent=r?r.memberName:"";
    if(r){const t=localTime(r);$("latest-time").textContent=t.date+" · "+names[t.period]+" "+t.clock;$("latest-pulse").textContent="脉搏 "+r.pulse+" 次/分";}
    else{$("latest-time").textContent="所选日期暂无测量";$("latest-pulse").textContent="脉搏 —";}
    const fields=multiple?[
      ["测量次数",s.count,"次","当前筛选范围的全部记录",null],
      ["记录天数",s.days,"天","有测量记录的日期数",null],
      ["记录家人",s.members,"位","不同家人的曲线分别呈现",null]
    ]:[
      ["平均高压",s.systolic,"mmHg",s.count?"范围 "+s.minSystolic+"–"+s.maxSystolic+" mmHg":"暂无测量记录","systolic"],
      ["平均低压",s.diastolic,"mmHg",s.count?"范围 "+s.minDiastolic+"–"+s.maxDiastolic+" mmHg":"暂无测量记录","diastolic"],
      ["平均脉搏",s.pulse,"次/分",s.count?"范围 "+s.minPulse+"–"+s.maxPulse+" 次/分":"暂无测量记录","pulse"]
    ];
    fields.forEach(([label,value,unit,foot,field],i)=>{
      const n=i+1;$("stat"+n+"-label").textContent=label;$("stat"+n+"-value").textContent=number(value,field?1:0);
      $("stat"+n+"-unit").textContent=unit;$("stat"+n+"-foot").textContent=foot;
      const graph=$("spark"+n);graph.replaceChildren();
      const values=field?overview.daily.map(d=>d[field]).slice(-24):[];
      if(values.length){const min=Math.min(...values),max=Math.max(...values),span=max-min||1;
        const coords=values.map((v,i)=>(values.length===1?100:i*200/(values.length-1))+","+(30-(v-min)*23/span)).join(" ");
        graph.append(svg("polyline",{points:coords,fill:"none",stroke:colors[field],"stroke-width":1.5}));
      }
    });
    $("coverage-count").textContent=s.days;$("measurement-count").textContent=s.count+" 次测量";
  }
  function renderPeriods() {
    const container=$("periods");container.replaceChildren();
    const multiple=!applied.member && overview.summary.members>1;
    $("period-caption").textContent=multiple?"选择一位家人，查看时段均值":"同一时段，更方便对照";
    const all=overview.periods,night=all.some(p=>p.period==="night");
    for(const period of ["morning","afternoon","evening",...(night?["night"]:[])]) {
      const matching=all.filter(p=>p.period===period),count=matching.reduce((sum,p)=>sum+p.count,0);
      const row=node("div","period-row "+period),title=node("div","period-title"),name=node("span","period-name");
      name.append(icon(period==="evening"||period==="night"?"moon":"sun"),document.createTextNode(names[period]));
      title.append(name,node("small","",count+" 次"));row.append(title);
      const values=node("div","period-values");
      if(multiple)values.append(document.createTextNode(count+" 次"),node("span","","测量记录"));
      else{const p=matching[0];values.append(document.createTextNode(p?number(p.systolic)+" / "+number(p.diastolic):"— / —"),node("span","","mmHg"),node("small","pulse",p?"脉搏 "+number(p.pulse):"脉搏 —"));}
      const bar=node("div","period-bar"),fill=node("span","");fill.style.width=(overview.summary.count?100*count/overview.summary.count:0)+"%";bar.append(fill);
      row.append(values,bar);container.append(row);
    }
  }
  function renderRows() {
    const list=$("records");list.replaceChildren();
    $("records-empty").hidden=rows.length>0;$("count").textContent="共 "+total+" 条";
    $("list-caption").textContent=mode==="reading"?"逐条核对原始测量值":"按测量时间从近到远排列";
    for(const r of rows) {
      const tr=node("tr",""),t=localTime(r),time=node("td","");
      const clock=node("span","record-clock");clock.append(node("span","period-tag "+t.period,names[t.period]),document.createTextNode(t.clock));
      time.append(node("span","record-date",t.date),clock);
      const member=node("td",""),who=node("div","record-member");who.append(node("span","avatar",Array.from(r.memberName).slice(0,1).join("")),document.createTextNode(r.memberName));member.append(who);
      tr.append(time,member);
      for(const [label,field,cls] of [["高压 · mmHg","systolic","coral"],["低压 · mmHg","diastolic","blue"],["脉搏 · 次/分","pulse","gold"]]) {
        const cell=node("td","");cell.dataset.label=label;cell.append(node("span","record-value "+cls,r[field]));tr.append(cell);
      }
      const note=node("td","");note.append(node("div","record-source",sources[r.source]||"历史记录"),node("div","record-note",r.note||"—"));
      const last=node("td",""),button=node("button","record-detail","↗");button.type="button";
      button.setAttribute("aria-label","查看 "+r.memberName+" "+t.date+" "+t.clock+" 的记录详情");button.addEventListener("click",()=>showRecord(r));last.append(button);
      tr.append(note,last);list.append(tr);
    }
    $("page").textContent=total?("第 "+(offset+1)+"–"+Math.min(offset+pageSize,total)+" 条 / "+total+" 条"):"0 条记录";
  }
  function showRecord(r) {
    const box=$("record-details");box.replaceChildren();const t=localTime(r);
    box.append(node("h2","",r.memberName),node("p","dialog-time",t.date+" · "+names[t.period]+" "+t.clock));
    const grid=node("div","dialog-readings");
    for(const [label,field,cls,unit] of [["高压","systolic","coral","mmHg"],["低压","diastolic","blue","mmHg"],["脉搏","pulse","gold","次/分"]]){
      const cell=node("div","dialog-reading");cell.append(node("span","",label),node("strong",cls,r[field]),node("small","",unit));grid.append(cell);
    }
    box.append(grid,node("p","dialog-note",r.note||"没有填写备注"),node("p","dialog-source",(sources[r.source]||"历史记录")+" · 在手机端编辑此记录"));$("record-dialog").showModal();
  }
  function colorFor(id,field) { return new Set(points.map(p=>p.deviceId)).size>1?palette[Math.max(0,devices.findIndex(d=>d.id===id))%palette.length]:colors[field]; }
  function chartPoints() {
    return mode==="daily"?overview.daily.map(d=>({...d,stamp:Date.parse(d.date+"T00:00:00Z")})):
      raw.map(r=>({...r,count:1,stamp:r.measuredAt})).sort((a,b)=>a.stamp-b.stamp || a.deviceId.localeCompare(b.deviceId));
  }
  function legend(label,color,dashed=false) {
    const e=node("span","legend-item"),line=svg("svg",{viewBox:"0 0 22 7","aria-hidden":"true"});
    line.append(svg("line",{x1:0,x2:22,y1:3.5,y2:3.5,stroke:color,"stroke-width":2,...(dashed?{"stroke-dasharray":"4 3"}:{})}));e.append(line,document.createTextNode(label));$("chart-legend").append(e);
  }
  function renderChart() {
    if(!overview)return;
    points=chartPoints();selected=points.length-1;positions=[];cursor=null;graphX=null;graphY=null;
    const graph=$("chart"),legendBox=$("chart-legend"),axis=$("chart-yaxis");graph.replaceChildren();legendBox.replaceChildren();axis.replaceChildren();axis.toggleAttribute("hidden",points.length===0);
    $("chart-empty").hidden=points.length>0;$("chart-scroll").hidden=points.length===0;
    const pressure=$("chart-metric").value==="pressure",fields=pressure?["systolic","diastolic"]:["pulse"];
    $("trend-caption").textContent=mode==="daily"?"按天呈现测量均值，缺测日不生成读数":"按实际测量时间呈现原始读数";
    $("chart-footnote").textContent=mode==="daily"?"每日均值按家人分别计算；横轴保留实际日期间隔。可左右滚动、点选或用方向键查看。":
      (rawTotal>200?"曲线显示最近 200 次测量；下方档案可分页查看全部记录。":"曲线显示所选范围内的逐次测量；横轴保留实际时间间隔。")+"横轴使用浏览器时区，详情保留手机测量时区。";
    if(!points.length){$("chart-detail").replaceChildren(node("span","muted","当前日期范围没有测量数据"));setPointButtons();return;}
    const ids=[...new Set(points.map(p=>p.deviceId))],multi=ids.length>1;
    if(multi){ids.forEach(id=>legend(points.find(p=>p.deviceId===id).memberName,colorFor(id,fields[0])));if(pressure)legend("高压实线 / 低压虚线","#9aad8b",true);}
    else fields.forEach(f=>legend(f==="systolic"?"高压":f==="diastolic"?"低压":"脉搏",colors[f],f==="diastolic"));
    const viewport=$("chart-scroll").clientWidth||650,unique=new Set(points.map(p=>p.stamp)).size;
    const width=Math.max(viewport,Math.min(12000,unique*(mode==="daily"?44:30))),height=250,left=46,right=20,top=24,bottom=36;
    graph.setAttribute("width",width);graph.setAttribute("height",height);graph.setAttribute("viewBox","0 0 "+width+" "+height);graph.style.width=width+"px";
    const values=points.flatMap(p=>fields.map(f=>p[f])),min=Math.max(0,Math.floor((Math.min(...values)-12)/20)*20),max=Math.ceil((Math.max(...values)+12)/20)*20;
    const minTime=points[0].stamp,maxTime=points[points.length-1].stamp;
    graphX=p=>minTime===maxTime?(width+left-right)/2:left+(p.stamp-minTime)/(maxTime-minTime)*(width-left-right);
    graphY=v=>top+(max-v)/(max-min)*(height-top-bottom);
    for(let i=0;i<=4;i++){const value=min+(max-min)*i/4,y=graphY(value);
      graph.append(svg("line",{class:"chart-grid",x1:left,x2:width-right,y1:y,y2:y}));const label=svg("text",{class:"chart-axis",x:left-9,y:y+4,"text-anchor":"end"});label.textContent=number(value);axis.append(label);
    }
    const unit=svg("text",{class:"chart-axis",x:4,y:12});unit.textContent=pressure?"mmHg":"次/分";axis.append(unit);
    const ticks=Math.max(2,Math.floor((width-left-right)/100)),count=minTime===maxTime?1:ticks;
    for(let i=0;i<count;i++){const stamp=count===1?minTime:minTime+(maxTime-minTime)*i/(count-1),d=new Date(stamp);
      const label=svg("text",{class:"chart-axis",x:graphX({stamp}),y:height-9,"text-anchor":i===0?"start":i===count-1?"end":"middle"});
      label.textContent=mode==="reading" ? (maxTime-minTime<86400000?pad(d.getHours())+":"+pad(d.getMinutes()):(d.getMonth()+1)+"/"+d.getDate()):(d.getUTCMonth()+1)+"/"+d.getUTCDate();graph.append(label);
    }
    positions=points.map(graphX);
    for(const id of ids)for(const field of fields){
      const group=points.filter(p=>p.deviceId===id),color=colorFor(id,field);
      graph.append(svg("polyline",{class:"chart-line",points:group.map(p=>graphX(p)+","+graphY(p[field])).join(" "),stroke:color,...(field==="diastolic"?{"stroke-dasharray":"5 4"}:{})}));
      if(points.length<=1000)group.forEach(p=>{const dot=svg("circle",{class:"chart-point",cx:graphX(p),cy:graphY(p[field]),r:3.6,fill:color});
        dot.addEventListener("pointerdown",event=>{event.stopPropagation();selectPoint(points.indexOf(p),false);});graph.append(dot);
      });
    }
    selectPoint(selected,false);
    $("chart-scroll").scrollLeft=width;
  }
  function setPointButtons() {$("point-previous").disabled=busy||selected<=0;$("point-next").disabled=busy||selected<0||selected>=points.length-1;}
  function selectPoint(index,scroll=true) {
    if(!points.length)return;selected=Math.max(0,Math.min(points.length-1,index));const p=points[selected],box=$("chart-detail");box.replaceChildren();
    let when;if(mode==="daily"){const [y,m,d]=p.date.split("-");when=y+"年"+Number(m)+"月"+Number(d)+"日";}
    else{const t=localTime(p);when=t.date+" "+names[t.period]+" "+t.clock;}
    box.append(node("strong","",p.memberName),node("span","detail-time",when));
    for(const [label,field,cls] of [["高压","systolic","coral"],["低压","diastolic","blue"],["脉搏","pulse","gold"]]){
      const e=node("span","detail-reading "+cls,label+" ");e.append(node("b","",number(p[field],mode==="daily"?1:0)));box.append(e);
    }
    if(mode==="daily")box.append(node("span","detail-count",p.count+" 次测量的均值"));
    if(cursor)cursor.remove();cursor=svg("g",{"aria-hidden":"true"});
    const x=positions[selected];cursor.append(svg("line",{class:"chart-cursor",x1:x,x2:x,y1:20,y2:215}));
    for(const field of ($("chart-metric").value==="pressure"?["systolic","diastolic"]:["pulse"]))cursor.append(svg("circle",{class:"chart-highlight",cx:x,cy:graphY(p[field]),r:5,stroke:colorFor(p.deviceId,field)}));
    $("chart").append(cursor);setPointButtons();
    if(scroll){const area=$("chart-scroll");if(x<area.scrollLeft+50 || x>area.scrollLeft+area.clientWidth-30)area.scrollLeft=Math.max(0,x-area.clientWidth/2);}
  }
  $("chart").addEventListener("pointermove",event=>{
    if(busy || !positions.length || event.pointerType==="touch")return;
    const x=event.clientX-$("chart").getBoundingClientRect().left;let best=0;
    positions.forEach((v,i)=>{if(Math.abs(v-x)<Math.abs(positions[best]-x))best=i;});selectPoint(best,false);
  });
  $("chart").addEventListener("pointerdown",event=>{
    if(busy || !positions.length)return;const x=event.clientX-$("chart").getBoundingClientRect().left;
    let best=0;positions.forEach((v,i)=>{if(Math.abs(v-x)<Math.abs(positions[best]-x))best=i;});selectPoint(best,false);
  });
  $("chart-scroll").addEventListener("keydown",event=>{
    if(event.key==="ArrowLeft"||event.key==="ArrowRight"){event.preventDefault();selectPoint(selected+(event.key==="ArrowRight"?1:-1));}
  });
  $("point-previous").addEventListener("click",()=>selectPoint(selected-1));$("point-next").addEventListener("click",()=>selectPoint(selected+1));
  function logout() {
    token="";overview=null;rows=[];raw=[];points=[];selected=-1;total=0;offset=0;devices=[];applied=null;
    $("records").replaceChildren();$("chart").replaceChildren();$("chart-yaxis").replaceChildren();$("periods").replaceChildren();$("member-list").replaceChildren();
    $("chart-detail").replaceChildren();$("latest-pressure").textContent="—";$("latest-member").textContent="";$("latest-time").textContent="暂无记录";$("latest-pulse").textContent="脉搏 —";
    for(let i=1;i<=3;i++){$("stat"+i+"-value").textContent="—";$("stat"+i+"-foot").textContent="暂无记录";$("spark"+i).replaceChildren();}
    $("page-title").textContent="家人的健康概览";$("page-subtitle").textContent="每一次记录，都让变化更清楚。";
    $("record-details").replaceChildren();if($("record-dialog").open)$("record-dialog").close();
    $("settings-members").replaceChildren();if($("settings-dialog").open)$("settings-dialog").close();
    $("records-panel").hidden=true;$("login").hidden=false;$("key").value="";$("key").focus();
  }
  $("login-form").addEventListener("submit",event=>{event.preventDefault();action(async()=>{
    token=$("key").value.trim();if(!token)throw new Error("请填写查看密钥。");
    await loadMembers(true);await loadAll();$("key").value="";$("login").hidden=true;$("records-panel").hidden=false;renderChart();window.scrollTo(0,0);
  });});
  $("logout").addEventListener("click",()=>{if(!busy){logout();message();setBusy(false);}});
  $("reload").addEventListener("click",()=>action(async()=>{await loadMembers();await loadAll();}));
  $("refresh").addEventListener("click",()=>action(loadAll));
  $("member").addEventListener("change",()=>action(loadAll));
  for(const field of ["start","end"])$(field).addEventListener("change",()=>{range="custom";updateRange();});
  document.querySelectorAll("[data-range]").forEach(b=>b.addEventListener("click",()=>action(async()=>{setRange(b.dataset.range);await loadAll();})));
  async function page(delta) {
    const nextOffset=Math.max(0,offset+delta*pageSize),p=new URLSearchParams(applied.query);p.set("limit",pageSize);p.set("offset",nextOffset);
    const result=await(await request("/api/v1/measurements?"+p)).json();
    total=result.total;offset=result.records.length||nextOffset===0?nextOffset:Math.max(0,Math.floor((total-1)/pageSize)*pageSize);
    if(offset!==nextOffset){p.set("offset",offset);rows=(await(await request("/api/v1/measurements?"+p)).json()).records;}
    else rows=result.records;
    renderRows();$("history").scrollIntoView({block:"start"});
  }
  $("previous").addEventListener("click",()=>action(()=>page(-1)));$("next").addEventListener("click",()=>action(()=>page(1)));
  async function setMode(next) {
    if(next==="reading" && mode!=="reading"){const p=new URLSearchParams(applied.query);p.set("limit",200);const result=await(await request("/api/v1/measurements?"+p)).json();raw=result.records;rawTotal=result.total;}
    mode=next;for(const [id,value] of [["daily-mode","daily"],["reading-mode","reading"]]){$(id).classList.toggle("selected",mode===value);$(id).setAttribute("aria-pressed",String(mode===value));}
    renderChart();
  }
  $("daily-mode").addEventListener("click",()=>action(()=>setMode("daily")));$("reading-mode").addEventListener("click",()=>action(()=>setMode("reading")));
  $("chart-metric").addEventListener("change",renderChart);
  $("export").addEventListener("click",()=>action(async()=>{
    const blob=await(await request("/api/v1/measurements.csv?"+applied.query)).blob(),url=URL.createObjectURL(blob),link=document.createElement("a");
    link.href=url;link.download="家人的血压记录.csv";document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }));
  $("close-dialog").addEventListener("click",()=>$("record-dialog").close());
  $("record-dialog").addEventListener("click",event=>{if(event.target===$("record-dialog"))$("record-dialog").close();});
  let resizeTimer;
  new ResizeObserver(()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(!$("records-panel").hidden)renderChart();},100);}).observe($("chart-scroll"));
  $("today").textContent=new Date().toLocaleDateString("zh-CN",{month:"long",day:"numeric",weekday:"long"});
  setRange("30");setBusy(false);
})();
