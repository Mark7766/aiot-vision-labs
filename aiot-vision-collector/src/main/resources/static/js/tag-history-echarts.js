/* Tag History Page using ECharts (history + prediction) */
(function(global){
  'use strict';
  const C = global.AiotCommon || {};
  const VERSION='tag-history-echarts-20251014c';
  const page = global.__PAGE_DATA__ || { entries:[], deviceId:0, tagId:0, tagName:'' };
  const state = {
    entries: Array.isArray(page.entries)? page.entries.slice(): [],
    points: [],          // numeric parsed history points [{t,y}]
    prediction: null,    // {points:[{t,y}], intervalMs}
    refreshTimer: null,
    chart: null,
    option: null,
    intervalMs: 0
  };
  const els = {};
  function qs(id){ return document.getElementById(id); }
  function initEls(){
    els.btnLine=qs('btnLine');
    els.btnTable=qs('btnTable');
    els.btnPredict=qs('btnPredict');
    els.predictStatus=qs('predictStatus');
    els.chartPanel=qs('chartPanel');
    els.tablePanel=qs('tablePanel');
    els.chartHint=qs('chartHint');
    els.metricBox=qs('metricBox');
    els.hoverReadout=qs('hoverReadout');
    els.tbody=qs('histTbody');
    els.noDataDyn=qs('noDataDyn');
    els.chartDiv=qs('histChart');
  }
  function ensureEntriesFromDom(){
    if(state.entries && state.entries.length) return;
    const rows = document.querySelectorAll('#histTbody tr');
    const tmp=[]; rows.forEach(r=>{ const t=r.children[0]?.textContent?.trim(); const v=r.children[1]?.textContent?.trim(); if(t) tmp.push({timestamp:t,value:v});});
    if(tmp.length) state.entries=tmp;
  }
  function parseHistory(){
    state.points = C.parseData(state.entries);
    state.intervalMs = C.inferIntervalMs(state.points) || 0;
  }
  function buildSeriesData(){
    const hist = state.points.map(p=>({ value:[p.t, p.y], ts:p.t, y:p.y }));
    const pred = (state.prediction?.points || []).map(p=>({ value:[p.t, p.y], ts:p.t, y:p.y }));
    return {hist, pred};
  }
  function computeMetrics(){
    const pts = state.points;
    const predPts = state.prediction?.points || [];
    const box = els.metricBox; if(!box){ return; }
    if(!pts.length){ box.style.display='none'; return; }
    if(!predPts.length){ box.innerHTML='预测指标: <small>MAE=--</small> | <small>RMSE=--</small>'; box.style.display=''; return; }
    // Align by nearest timestamp within tolerance (half interval or 30s fallback)
    let interval = state.prediction.intervalMs || state.intervalMs;
    if(!interval && predPts.length>1){ const dif=[]; for(let i=1;i<predPts.length;i++){ const d=predPts[i].t - predPts[i-1].t; if(d>0) dif.push(d);} if(dif.length){ dif.sort((a,b)=>a-b); interval=dif[Math.floor(dif.length/2)]; } }
    const tol = Math.max(1000, interval? Math.floor(interval/2): 30000);
    const actualMap = new Map(pts.map(p=>[p.t,p.y]));
    const actualTimes = pts.map(p=>p.t);
    const matched=[];
    for(const fp of predPts){ let bestT=null, bestD=Number.MAX_VALUE; for(const t of actualTimes){ const d=Math.abs(t-fp.t); if(d<bestD && d<=tol){ bestD=d; bestT=t; } } if(bestT!=null){ matched.push({a:actualMap.get(bestT), p:fp.y}); } }
    if(!matched.length){ box.innerHTML='预测指标: <small>MAE=--</small> | <small>RMSE=--</small> <small style="color:var(--color-text-dim);">(预测无可对齐点)</small>'; box.style.display=''; return; }
    let ae=0,se=0; for(const m of matched){ const err=m.a-m.p; ae+=Math.abs(err); se+=err*err; }
    const n=matched.length; box.innerHTML=`预测指标: <small>样本=${n}</small> | <small>MAE=${(ae/n).toFixed(4)}</small> | <small>RMSE=${Math.sqrt(se/n).toFixed(4)}</small>`; box.style.display='';
  }
  function renderTable(){
    if(!els.tbody) return;
    els.tbody.innerHTML=(state.entries||[]).map(e=>`<tr><td style="font-family:var(--font-mono);">${e.timestamp||''}</td><td style="font-family:var(--font-mono);">${e.value||''}</td></tr>`).join('');
  }
  function showLine(){ els.chartPanel.style.display=''; els.tablePanel.style.display='none'; els.btnLine.classList.add('active'); els.btnTable.classList.remove('active'); resizeChart(); }
  function showTable(){ els.chartPanel.style.display='none'; els.tablePanel.style.display=''; els.btnTable.classList.add('active'); els.btnLine.classList.remove('active'); renderTable(); }
  function updatePanels(){
    const hasData = state.entries && state.entries.length;
    if(!hasData){ if(els.noDataDyn) els.noDataDyn.style.display=''; els.chartPanel.style.display='none'; els.tablePanel.style.display='none'; return; }
    if(els.noDataDyn) els.noDataDyn.style.display='none';
    if(els.btnLine.classList.contains('active')){ els.chartPanel.style.display=''; els.tablePanel.style.display='none'; drawChart(); } else { els.chartPanel.style.display='none'; els.tablePanel.style.display=''; renderTable(); }
  }
  function initChart(){
    if(!els.chartDiv) return;
    state.chart = echarts.init(els.chartDiv, null, {renderer:'canvas'});
    state.chart.getZr().on('globalout', ()=>{ if(els.hoverReadout) els.hoverReadout.textContent=''; });
  }
  function tooltipFormatter(params){
    if (!params || !params.length) return '';

    let axisTs = null;
    const p0 = params[0];
    if (p0) {
      if (Array.isArray(p0.value)) axisTs = p0.value[0];
      else if (typeof p0.value === 'number') axisTs = p0.value;
      else if (p0.axisValue) axisTs = p0.axisValue;
    }
    if (axisTs == null || isNaN(axisTs)) {
      const parsed = Date.parse(p0?.axisValueLabel || '');
      if (!isNaN(parsed)) axisTs = parsed;
      else axisTs = Date.now();
    }

    const d = new Date(axisTs);
    const ts = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;

    let content = `<div style="font-size:11px;">${VERSION}<br/>${ts}<br/>`;

    // 遍历所有曲线，显示名称和值
    params.forEach(p => {
      const name = p.seriesName || '';
      let val = '--';
      if (Array.isArray(p.value)) val = Number(p.value[1]).toFixed(3);
      else if (typeof p.value === 'number') val = Number(p.value).toFixed(3);
      content += `${name}: ${val}<br/>`;
    });

    content += '</div>';

    // 同步到 hoverReadout
    if (els.hoverReadout) {
      let histVal = '--', predVal = '--';
      params.forEach(p => {
        if (p.seriesName === '历史' && Array.isArray(p.value)) histVal = Number(p.value[1]).toFixed(3);
        if (p.seriesName === '预测' && Array.isArray(p.value)) predVal = Number(p.value[1]).toFixed(3);
      });
      els.hoverReadout.textContent = `${ts}  历史: ${histVal}  预测: ${predVal}`;
    }

    return content;
  }
  function drawChart(){
    parseHistory();
    if(!state.points.length){ els.chartHint.style.display=''; showTable(); return; }
    els.chartHint.style.display='none';
    if(!state.chart) initChart(); if(!state.chart) return;
    const hist=state.points.map(p=>({ value:[p.t,p.y], t:p.t, y:p.y }));
    const pred=(state.prediction?.points||[]).map(p=>({ value:[p.t,p.y], t:p.t, y:p.y }));
    const allY=hist.map(p=>p.y).concat(pred.map(p=>p.y));
    let yMin=allY.length?Math.min(...allY):0, yMax=allY.length?Math.max(...allY):1; if(yMin===yMax){ yMin-=1; yMax+=1; }
    const sample=[yMin,yMax,(yMin+yMax)/2,(yMin*0.25+yMax*0.75),(yMin*0.75+yMax*0.25)];
    const longest=sample.map(v=>v.toFixed(2).length).reduce((m,n)=>Math.max(m,n),0);
    const dynamicLeft=Math.min(150, Math.max(70, longest*9+22));
    state.option={
      animation:true,
      backgroundColor:'transparent',
      grid:{ left:dynamicLeft, right:55, top:18, bottom:68, containLabel:true },
      tooltip:{ trigger:'axis', axisPointer:{ type:'cross' }, formatter:tooltipFormatter, confine:true, borderWidth:1, borderColor:'#00bfff', backgroundColor:'rgba(0,0,0,0.75)', textStyle:{fontSize:11} },
      xAxis:{ type:'time', boundaryGap:false, axisLine:{lineStyle:{color:'#226484'}}, axisLabel:{color:'#7fdcff'} },
      yAxis:{ type:'value', min:yMin, max:yMax, scale:true, axisLine:{lineStyle:{color:'#226484'}}, splitLine:{lineStyle:{color:'rgba(0,191,255,0.15)'}}, axisLabel:{color:'#7fdcff', margin:12, hideOverlap:true} },
      legend:{ show:false },
