"use strict";
(() => {
  const byId = id => document.getElementById(id);
  let token = "", offset = 0, total = 0, busy = false;
  const pageSize = 50;
  const controls = ["connect", "refresh", "export", "previous", "next", "member", "start", "end"];
  function message(text = "") { byId("message").textContent = text; }
  function setBusy(value) {
    busy = value;
    controls.forEach(id => { byId(id).disabled = value; });
    if (!value) {
      byId("previous").disabled = offset === 0;
      byId("next").disabled = offset + pageSize >= total;
    }
  }
  async function request(path) {
    const response = await fetch(path, {headers:{Authorization:"Bearer " + token}, cache:"no-store", redirect:"error"});
    if (!response.ok) throw new Error(response.status === 401 ? "查看密钥不正确或已被替换。" : "无法读取服务器记录，请稍后再试。");
    return response;
  }
  function selection() {
    const params = new URLSearchParams();
    const start = byId("start").value, end = byId("end").value;
    if (start && end && start > end) throw new Error("开始日期不能晚于结束日期。");
    if (byId("member").value) params.set("device_id", byId("member").value);
    if (start) params.set("from_ms", new Date(start + "T00:00:00").getTime());
    if (end) {
      const date = new Date(end + "T00:00:00");
      date.setDate(date.getDate() + 1);
      params.set("to_ms", date.getTime());
    }
    return params;
  }
  function node(tag, className, text) {
    const element = document.createElement(tag);
    element.className = className;
    element.textContent = text;
    return element;
  }
  function timeText(record) {
    // Use the offset recorded on the phone, rather than the viewing computer's timezone.
    const date = new Date(record.measuredAt + record.utcOffsetMinutes * 60000);
    const pad = value => String(value).padStart(2, "0");
    const hour = date.getUTCHours();
    const period = hour < 5 ? "凌晨" : hour < 12 ? "上午" : hour < 18 ? "下午" : "晚上";
    return `${date.getUTCFullYear()}年${date.getUTCMonth()+1}月${date.getUTCDate()}日 · ${period} ${pad(hour)}:${pad(date.getUTCMinutes())}`;
  }
  function render(records) {
    const list = byId("records");
    list.replaceChildren();
    if (!records.length) list.append(node("div", "panel empty", "这个范围内暂无上传记录"));
    for (const record of records) {
      const card = node("article", "record", "");
      const heading = node("div", "record-heading", "");
      heading.append(node("span", "member-name", record.memberName),
        node("span", "source", ({camera:"拍照录入",manual:"手动录入",import:"导入记录",legacy:"历史记录"})[record.source] || "历史记录"));
      card.append(heading, node("div", "time", timeText(record)));
      const grid = node("div", "reading-grid", "");
      for (const [label, value, color, unit] of [["高压", record.systolic, "systolic", "mmHg"],
        ["低压", record.diastolic, "diastolic", "mmHg"], ["脉搏", record.pulse, "pulse", "次/分"]]) {
        const reading = node("div", "", "");
        reading.append(node("div", "reading-label", label), node("div", "reading-value " + color, value), node("div", "unit", unit));
        grid.append(reading);
      }
      card.append(grid);
      if (record.note.trim()) card.append(node("p", "note", "备注：" + record.note));
      list.append(card);
    }
    byId("count").textContent = `共 ${total} 条`;
    byId("page").textContent = total ? `${offset + 1}–${Math.min(offset + pageSize, total)} / ${total}` : "0 条";
  }
  async function load() {
    const params = selection();
    params.set("limit", pageSize); params.set("offset", offset);
    const result = await (await request("/api/v1/measurements?" + params)).json();
    total = result.total; render(result.records);
  }
  async function action(callback) {
    if (busy) return;
    message(); setBusy(true);
    try { await callback(); } catch (error) { message(error.message || "网络连接失败，请稍后再试。"); }
    finally { setBusy(false); }
  }
  byId("connect").addEventListener("click", () => action(async () => {
    token = byId("key").value.trim();
    if (!token) throw new Error("请填写查看密钥。");
    const members = await (await request("/api/v1/devices")).json();
    byId("member").replaceChildren(new Option("全部家人", ""));
    members.devices.forEach(member => byId("member").add(new Option(member.name, member.id)));
    offset = 0; await load();
    byId("key").value = "";
    byId("login").hidden = true; byId("records-panel").hidden = false;
  }));
  byId("key").addEventListener("keydown", event => { if (event.key === "Enter") byId("connect").click(); });
  byId("refresh").addEventListener("click", () => action(async () => { offset = 0; await load(); }));
  byId("previous").addEventListener("click", () => action(async () => { offset = Math.max(0, offset-pageSize); await load(); }));
  byId("next").addEventListener("click", () => action(async () => { offset += pageSize; await load(); }));
  byId("export").addEventListener("click", () => action(async () => {
    const blob = await (await request("/api/v1/measurements.csv?" + selection())).blob();
    const url = URL.createObjectURL(blob), link = document.createElement("a");
    link.href = url; link.download = "家人的血压记录.csv"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }));
  byId("logout").addEventListener("click", () => {
    if (busy) return;
    token = ""; offset = 0; total = 0; message();
    byId("records").replaceChildren(); byId("login").hidden = false; byId("records-panel").hidden = true;
  });
})();
