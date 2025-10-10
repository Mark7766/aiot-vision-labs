/* Tag History Page Module */
(function(global){
  'use strict';
  const C = global.AiotCommon || {};
  const page = global.__PAGE_DATA__ || { entries: [], deviceId: 0, tagId: 0, tagName: '' };
  const state = {
    entries: Array.isArray(page.entries) ? page.entries : [],
    prediction: null, // {points:[{t,y}], intervalMs}
    refreshTimer: null,
    chartMeta: null
  };
  // Elements
  const els = {};
  function qs(id){ return document.getElementById(id); }
  function initElements(){
    els.btnLine = qs('btnLine');
    els.btnTable = qs('btnTable');
    els.btnPredict = qs('btnPredict');
    els.predictStatus = qs('predictStatus');
    els.chartPanel = qs('chartPanel');
    els.tablePanel = qs('tablePanel');
    els.hint = qs('chartHint');
    els.canvas = qs('histCanvas');
    els.metricBox = qs('metricBox');
    els.noDataDyn = qs('noDataDyn');
    els.tbody = qs('histTbody');
    els.tooltip = qs('chartTooltip');
    if(els.canvas) els.ctx = els.canvas.getContext('2d');
  }

  function ensureEntriesFromDom(){
    if(state.entries && state.entries.length) return;
    const rows = document.querySelectorAll('#histTbody tr');
    const tmp=[]; rows.forEach(r=>{ const t=r.children[0]?.textContent?.trim(); const v=r.children[1]?.textContent?.trim(); if(t) tmp.push({timestamp:t,value:v}); });
    state.entries=tmp;
  }

  function resizeCanvas(){ if(!els.canvas) return; const ratio=Math.max(global.devicePixelRatio||1,1); const rect=els.canvas.getBoundingClientRect(); const w=Math.floor(rect.width*ratio); const h=Math.floor(rect.height*ratio); if(els.canvas.width!==w||els.canvas.height!==h){ els.canvas.width=w; els.canvas.height=h; els.ctx=els.canvas.getContext('2d'); } }

  function drawChart(){
    if(!els.canvas) return; resizeCanvas(); const ctx=els.ctx; const W=els.canvas.width, H=els.canvas.height; ctx.clearRect(0,0,W,H);
    const pts=C.parseData(state.entries); const fpts=(state.prediction&&state.prediction.points)||[];
    if(!pts.length){ // switch to table
      els.chartPanel.style.display='none'; els.tablePanel.style.display=''; els.btnTable?.classList.add('active'); els.btnLine?.classList.remove('active'); els.hint.style.display=(state.entries&&state.entries.length)?'':'none'; return; }
    els.hint.style.display='none';
    const all=fpts.length?pts.concat(fpts):pts; const tMin=Math.min(...all.map(p=>p.t)); const tMax=Math.max(...all.map(p=>p.t)); let yMin=Math.min(...all.map(p=>p.y)); let yMax=Math.max(...all.map(p=>p.y)); if(yMin===yMax){ yMin-=1; yMax+=1; } const pad=(yMax-yMin)*0.1; yMin-=pad; yMax+=pad; const L=60,R=20,T=20,B=40;
    ctx.strokeStyle='rgba(0,191,255,0.35)'; ctx.lineWidth=1; ctx.strokeRect(L,T,W-L-R,H-T-B);
    ctx.textAlign='right'; ctx.textBaseline='middle'; ctx.font=`${12*(global.devicePixelRatio||1)}px monospace`;
    for(let i=0;i<=4;i++){ const yy=T+(H-T-B)*(i/4); const v=yMax-(yMax-yMin)*(i/4); ctx.strokeStyle='rgba(0,191,255,0.15)'; ctx.beginPath(); ctx.moveTo(L,yy); ctx.lineTo(W-R,yy); ctx.stroke(); ctx.fillStyle='#7fdcff'; ctx.fillText(v.toFixed(2),L-6,yy);} ctx.textAlign='center'; ctx.textBaseline='top';
    for(let i=0;i<=4;i++){ const ratio=(i/4); const xx=L+(W-L-R)*ratio; const tt=new Date(tMin+(tMax-tMin)*ratio); const label=`${tt.getHours().toString().padStart(2,'0')}:${tt.getMinutes().toString().padStart(2,'0')}:${tt.getSeconds().toString().padStart(2,'0')}`; ctx.fillStyle='#7fdcff'; ctx.fillText(label,xx,H-B+6);} // history
    ctx.setLineDash([]); ctx.strokeStyle='#00bfff'; ctx.lineWidth=2; ctx.beginPath(); for(let i=0;i<pts.length;i++){ const p=pts[i]; const xr=(tMax===tMin)?(i/(Math.max(pts.length-1,1))):((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; if(i===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);} ctx.stroke(); ctx.fillStyle='#7fdcff'; for(const p of pts){ const xr=(tMax===tMin)?0:((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; ctx.beginPath(); ctx.arc(x,y,2.5*(global.devicePixelRatio||1),0,Math.PI*2); ctx.fill(); }
    if(fpts.length){ ctx.setLineDash([6,4]); ctx.strokeStyle='#ffa500'; ctx.beginPath(); for(let i=0;i<fpts.length;i++){ const p=fpts[i]; const xr=(tMax===tMin)?(i/(Math.max(fpts.length-1,1))):((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; if(i===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);} ctx.stroke(); ctx.setLineDash([]);} state.chartMeta={tMin,tMax,yMin,yMax,L,R,T,B,W,H,pts,fpts}; updateAccuracy(); }

  function renderTable(){ if(!els.tbody) return; els.tbody.innerHTML=(state.entries||[]).map(e=>`<tr><td style="font-family:var(--font-mono);">${e.timestamp||''}</td><td style="font-family:var(--font-mono);">${e.value||''}</td></tr>`).join(''); }

  function updatePanels(){ const hasData = state.entries && state.entries.length; if(!hasData){ if(els.noDataDyn) els.noDataDyn.style.display=''; els.chartPanel.style.display='none'; els.tablePanel.style.display='none'; return; } else if(els.noDataDyn){ els.noDataDyn.style.display='none'; }
    const lineActive = els.btnLine && els.btnLine.classList.contains('active'); if(lineActive){ els.chartPanel.style.display=''; els.tablePanel.style.display='none'; drawChart(); } else { els.chartPanel.style.display='none'; els.tablePanel.style.display=''; renderTable(); } }

  function fetchLatest(){ if(!page.deviceId || !page.tagId) return; C.apiGet(`/data/api/history/${page.deviceId}/${page.tagId}`).then(list=>{ if(Array.isArray(list)){ state.entries=list; updatePanels(); } }).catch(()=>{}); }

  function showLine(){ els.chartPanel.style.display=''; els.tablePanel.style.display='none'; els.btnLine.classList.add('active'); els.btnTable.classList.remove('active'); drawChart(); }
  function showTable(){ els.chartPanel.style.display='none'; els.tablePanel.style.display=''; els.btnTable.classList.add('active'); els.btnLine.classList.remove('active'); renderTable(); }

  function updateAccuracy(){ const pts=C.parseData(state.entries); const fpts=(state.prediction&&state.prediction.points)||[]; if(!pts.length||!fpts.length){ els.metricBox.style.display='none'; els.metricBox.innerHTML=''; return; } const interval=state.prediction.intervalMs||C.inferIntervalMs(pts)||0; const tol=Math.max(1,Math.floor(interval/2)); const actualMap=new Map(); for(const p of pts) actualMap.set(p.t,p.y); const aTs=pts.map(p=>p.t); const matched=[]; for(const fp of fpts){ let bestT=null,bestD=Number.MAX_VALUE; for(const t of aTs){ const d=Math.abs(t-fp.t); if(d<bestD&&d<=tol){ bestD=d; bestT=t; } } if(bestT!=null){ matched.push({pred:fp.y,actual:actualMap.get(bestT)}); } } if(!matched.length){ els.metricBox.style.display='none'; els.metricBox.innerHTML=''; return; } let se=0,ae=0,mapeSum=0,mapeCnt=0; for(const m of matched){ const err=m.actual-m.pred; ae+=Math.abs(err); se+=err*err; if(Math.abs(m.actual)>1e-6){ mapeSum+=Math.abs(err/m.actual); mapeCnt++; } } const n=matched.length; const mae=ae/n, rmse=Math.sqrt(se/n), mape=mapeCnt?(mapeSum/mapeCnt*100):null; els.metricBox.innerHTML=`评估: <small>样本=${n}</small> | <small>MAE=${mae.toFixed(4)}</small> | <small>RMSE=${rmse.toFixed(4)}</small>${mape!=null?` | <small>MAPE=${mape.toFixed(2)}%</small>`:''}`; els.metricBox.style.display=''; }

  function doPredict(){ const pts=C.parseData(state.entries); if(!pts.length){ alert('当前没有可用于预测的数值数据'); return; } els.btnPredict.disabled=true; els.predictStatus.style.display=''; C.apiGet(`/data/api/predict/${page.deviceId}/${page.tagId}`).then(data=>{ const raw=Array.isArray(data?.predictionPoints)?data.predictionPoints:[]; const tmp=[]; for(const pp of raw){ if(!pp||!pp.timestamp||!C.isNumeric?.(pp.value)) continue; const t=C.parseTs(pp.timestamp); if(isNaN(t)) continue; tmp.push({t,y:Number(pp.value)});} tmp.sort((a,b)=>a.t-b.t); if(!tmp.length){ state.prediction=null; updatePanels(); return; } let intervalMs=0; if(tmp.length>1){ const diffs=[]; for(let i=1;i<tmp.length;i++){ const d=tmp[i].t-tmp[i-1].t; if(d>0) diffs.push(d);} if(diffs.length){ diffs.sort((a,b)=>a-b); intervalMs=diffs[Math.floor(diffs.length/2)]; } } state.prediction={points:tmp,intervalMs}; updatePanels(); }).catch(()=>{}).finally(()=>{ els.btnPredict.disabled=false; els.predictStatus.style.display='none'; }); }

  function handleTooltipMove(e){ if(!state.chartMeta){ els.tooltip.style.display='none'; return; } const {tMin,tMax,L,R,T,B,W,H,pts,fpts}=state.chartMeta; const rect=els.canvas.getBoundingClientRect(); const ratio=global.devicePixelRatio||1; const x=(e.clientX-rect.left)*ratio; const y=(e.clientY-rect.top)*ratio; if(x<L||x>W-R||y<T||y>H-B){ els.tooltip.style.display='none'; return; } const t=tMin+(tMax-tMin)*((x-L)/(W-L-R)); function nearest(list){ if(!list.length) return null; let best=null,bd=Number.MAX_VALUE; for(const p of list){ const d=Math.abs(p.t-t); if(d<bd){ bd=d; best=p; } } return {p:best,dt:bd}; } const nHist=nearest(pts); const nPred=nearest(fpts); if(!nHist && !nPred){ els.tooltip.style.display='none'; return; } const lines=[]; let showTs=t; if(nHist&&nHist.p){ lines.push(`历史: ${nHist.p.y.toFixed(3)}`); showTs=nHist.p.t; } if(nPred&&nPred.p){ lines.push(`预测: ${nPred.p.y.toFixed(3)}`); if(!nHist || (nPred.dt<nHist.dt)) showTs=nPred.p.t; } const d=new Date(showTs); const tsStr=`${d.getFullYear()}-${(d.getMonth()+1+'').padStart(2,'0')}-${(d.getDate()+'').padStart(2,'0')} ${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}:${d.getSeconds().toString().padStart(2,'0')}`; lines.unshift(tsStr); els.tooltip.innerHTML=lines.join('<br/>'); els.tooltip.style.display=''; els.tooltip.style.left=(e.clientX+12)+'px'; els.tooltip.style.top=(e.clientY+12)+'px'; }

  function attachEvents(){ els.btnLine?.addEventListener('click', showLine); els.btnTable?.addEventListener('click', showTable); els.btnPredict?.addEventListener('click', doPredict); global.addEventListener('resize', ()=>{ if(els.chartPanel && els.chartPanel.style.display!=='none') drawChart(); }); if(els.canvas){ els.canvas.addEventListener('mousemove', handleTooltipMove); els.canvas.addEventListener('mouseleave', ()=>{ els.tooltip.style.display='none'; }); }
    document.addEventListener('visibilitychange', ()=>{ if(document.hidden){ if(state.refreshTimer){ clearInterval(state.refreshTimer); state.refreshTimer=null; } } else { if(!state.refreshTimer){ fetchLatest(); state.refreshTimer=setInterval(fetchLatest,5000); } } }); }

  function start(){ initElements(); ensureEntriesFromDom(); showLine(); updatePanels(); fetchLatest(); state.refreshTimer=setInterval(fetchLatest,1000); }
  if(document.readyState==='loading'){ document.addEventListener('DOMContentLoaded', start); } else { start(); }
})(window);

