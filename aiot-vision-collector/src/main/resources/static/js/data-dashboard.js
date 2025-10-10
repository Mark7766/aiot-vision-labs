/* Data (Real-time dashboard) large script extracted */
(function(global){
  'use strict';
  const C = global.AiotCommon || {};
  let auto=false,timer=null; const DRAWER_REFRESH_MS=1000; let drawerOpen=false,currentDeviceId=null,currentTagId=null,currentTagName=''; let prediction=null; let drawerInterval=null; let drawerFetchInFlight=false; let chartMeta=null; // for history drawer
  // Elements lazy via ids
  const els = {};
  function id(x){ return document.getElementById(x); }

  // Auto refresh toggle (updated for icon button UI)
  function toggleAuto(){
    auto = !auto;
    const btn = id('autoBtn');
    if(!btn) return;
    btn.setAttribute('aria-pressed', auto ? 'true' : 'false');
    const labelEl = btn.querySelector('.visually-hidden');
    const statusEl = id('autoStatus');
    const liveEl = id('autoLive');
    if(auto){
      btn.classList.add('active');
      btn.title = '\u5173\u95ed\u81ea\u52a8\u5237\u65b0';
      btn.setAttribute('aria-label','\u5173\u95ed\u81ea\u52a8\u5237\u65b0');
      if(labelEl) labelEl.textContent='\u5173\u95ed\u81ea\u52a8\u5237\u65b0';
      if(statusEl){ statusEl.style.display='inline-flex'; statusEl.setAttribute('aria-hidden','false'); }
      if(liveEl){ liveEl.textContent='\u81ea\u52a8\u5237\u65b0\u5df2\u5f00\u542f\uff0c\u6bcf5\u79d2\u66f4\u65b0'; }
      fetchAndRender();
      timer = setInterval(fetchAndRender,5000);
    } else {
      btn.classList.remove('active');
      btn.title = '\u5f00\u542f\u81ea\u52a8\u5237\u65b0';
      btn.setAttribute('aria-label','\u5f00\u542f\u81ea\u52a8\u5237\u65b0');
      if(labelEl) labelEl.textContent='\u5f00\u542f\u81ea\u52a8\u5237\u65b0';
      if(timer) clearInterval(timer);
      if(statusEl){ statusEl.style.display='none'; statusEl.setAttribute('aria-hidden','true'); }
      if(liveEl){ liveEl.textContent='\u81ea\u52a8\u5237\u65b0\u5df2\u5173\u95ed'; }
    }
  }

  function escHtml(str){ return C.escHtml?C.escHtml(str): (str==null?'':String(str)); }

  let tagPagerState = {}; // { deviceId: { all:[], filters:{name:'',address:'',value:''}, page:1, pageSize:10 } }
  function ensureTagState(deviceId, tags){
    if(!tagPagerState[deviceId]){
      tagPagerState[deviceId] = { all: tags||[], filters:{name:'',address:'',value:''}, page:1, pageSize:10 };
    } else if(tags){
      const st=tagPagerState[deviceId];
      st.all = tags; // keep existing filters/page but clamp page later
    }
    return tagPagerState[deviceId];
  }
  function applyTagFilters(st){
    const {filters} = st;
    const fName = filters.name.trim().toLowerCase();
    const fAddr = filters.address.trim().toLowerCase();
    const fVal = filters.value.trim().toLowerCase();
    let list = st.all;
    if(fName) list = list.filter(t=> (t.name||'').toLowerCase().includes(fName));
    if(fAddr) list = list.filter(t=> (t.address||'').toLowerCase().includes(fAddr));
    if(fVal) list = list.filter(t=> String(t.value??'').toLowerCase().includes(fVal));
    return list;
  }
  function renderDeviceTags(deviceId){
    const body = document.getElementById('tagBody-'+deviceId);
    const pagerBox = document.getElementById('tagPager-'+deviceId);
    if(!body) return;
    const st = tagPagerState[deviceId];
    if(!st){ body.innerHTML='<tr><td colspan="3" class="text-dim" style="padding:.35rem .3rem;">无数据</td></tr>'; return; }
    const filtered = applyTagFilters(st);
    const total = filtered.length;
    const pages = Math.max(1, Math.ceil(total / st.pageSize));
    if(st.page>pages) st.page=pages;
    const start = (st.page-1)*st.pageSize;
    const slice = filtered.slice(start, start+st.pageSize);
    if(slice.length===0){ body.innerHTML='<tr><td colspan="3" class="text-warn" style="padding:.4rem;">无匹配结果</td></tr>'; }
    else { body.innerHTML = slice.map(t=>`<tr class='tag-row' data-device-id='${deviceId}' data-tag-id='${t.id??''}' data-tag-name='${escHtml(t.name??'')}' onclick='event.stopPropagation();'><td>${escHtml(t.name)}</td><td>${escHtml(t.address||'')}</td><td style='font-family:var(--font-mono);'>${escHtml(t.value||'')}</td></tr>`).join(''); }
    attachTagRowEvents(body);
    // pager
    if(pagerBox){
      if(total<=st.pageSize){ pagerBox.style.display='none'; }
      else {
        pagerBox.style.display='flex';
        pagerBox.querySelector('.pager-info').textContent = `${st.page} / ${pages} (共${total})`;
        const prevBtn=pagerBox.querySelector('.pager-prev');
        const nextBtn=pagerBox.querySelector('.pager-next');
        prevBtn.disabled = st.page<=1;
        nextBtn.disabled = st.page>=pages;
      }
    }
    // update filter badges / indicators
    updateFilterIndicators(deviceId);
  }
  function updateFilterIndicators(deviceId){
    const st = tagPagerState[deviceId];
    if(!st) return;
    ['name','address','value'].forEach(col=>{
      const btn = document.querySelector(`#filterBtn-${col}-${deviceId}`);
      if(btn){
        if(st.filters[col]) btn.classList.add('active-filter'); else btn.classList.remove('active-filter');
      }
    });
  }
  function openFilterPop(deviceId,col){
    const pop = document.getElementById(`filterPop-${col}-${deviceId}`);
    if(!pop) return; // toggle
    const visible = pop.getAttribute('data-open')==='1';
    document.querySelectorAll(`.filter-pop[data-device='${deviceId}']`).forEach(p=>{ p.style.display='none'; p.setAttribute('data-open','0'); });
    if(!visible){
      pop.style.display='block';
      pop.setAttribute('data-open','1');
      const st = tagPagerState[deviceId];
      const input = pop.querySelector('input');
      if(input && st) input.value = st.filters[col]||'';
      setTimeout(()=>input&&input.focus(),10);
    }
  }
  function applyFilter(deviceId,col){
    const st = tagPagerState[deviceId];
    if(!st) return;
    const pop = document.getElementById(`filterPop-${col}-${deviceId}`);
    if(pop){
      const val = pop.querySelector('input').value;
      st.filters[col]=val;
      st.page=1;
      pop.style.display='none'; pop.setAttribute('data-open','0');
      renderDeviceTags(deviceId);
    }
  }
  function clearFilter(deviceId,col){
    const st = tagPagerState[deviceId];
    if(!st) return;
    st.filters[col]=''; st.page=1; renderDeviceTags(deviceId);
    const pop = document.getElementById(`filterPop-${col}-${deviceId}`);
    if(pop){ const inp=pop.querySelector('input'); if(inp) inp.value=''; pop.style.display='none'; pop.setAttribute('data-open','0'); }
  }
  function changeTagPage(deviceId,delta){
    const st = tagPagerState[deviceId];
    if(!st) return; st.page += delta; if(st.page<1) st.page=1; renderDeviceTags(deviceId);
  }

  function fetchAndRender(){ fetch('/data/api/latest').then(r=>r.json()).then(list=>{ const grid=document.querySelector('.grid'); if(!grid) return; grid.innerHTML=''; if(!list||list.length===0){ return; } list.forEach(dev=>{ const card=document.createElement('div'); card.className='card'; card.dataset.deviceId=dev.deviceId; card.dataset.deviceName=dev.deviceName; card.dataset.deviceProtocol=dev.protocol; card.dataset.deviceConn=dev.connectionString; const connOk=dev.connectionOk===true; const deviceId=dev.deviceId; ensureTagState(deviceId, dev.tags||[]);
        card.innerHTML=`<div class='dev-actions' style='position:absolute;top:.35rem;right:.35rem;display:flex;gap:.35rem;'>
          <button type='button' class='delete-x btn-icon danger' title='删除设备' aria-label='删除设备' onclick='deleteDevice(${dev.deviceId},this);event.stopPropagation();'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M6 7h12M10 7V5h4v2m-7 0v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V7M9 11v6M15 11v6' stroke-linecap='round' stroke-linejoin='round'/></svg><span class='visually-hidden'>删除设备</span></button>
        </div>
        <div class='device-title' style='display:flex;align-items:center;gap:.4rem;margin:0 0 .1rem;'>
          <h3 style='margin:0;padding-right:2.2rem;'>${escHtml(dev.deviceName)}</h3>
          <button type='button' class='btn-icon' title='编辑设备' aria-label='编辑设备' onclick='openEditDevice(${dev.deviceId});event.stopPropagation();'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M4 17.5V20h2.5L17.81 8.69a1 1 0 0 0 0-1.41L15.72 5.19a1 1 0 0 0-1.41 0L5.5 14.5M13.5 6.5l2 2' stroke-linecap='round' stroke-linejoin='round'/></svg><span class='visually-hidden'>编辑设备</span></button>
        </div>
        <p class='text-dim' style='margin:0.3rem 0;font-size:.7rem;letter-spacing:.5px;'>协议: ${escHtml(dev.protocol||'')} | 连接: <strong style='color:${connOk?'var(--color-ok)':'var(--color-danger)'};'>${connOk?'正常':'失败'}</strong></p><div class='inline-actions' style='margin:0.3rem 0;flex-wrap:wrap;gap:.4rem;'><button type='button' class='btn-icon ok' title='添加点位' aria-label='添加点位' onclick='openNamespaces(${dev.deviceId});event.stopPropagation();'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M12 5v14M5 12h14' stroke-linecap='round'/></svg><span class='visually-hidden'>添加点位</span></button><button type='button' class='btn-icon' title='编辑点位' aria-label='编辑点位' onclick='openEditTags(${dev.deviceId});event.stopPropagation();'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M4 17.5V20h2.5L17.81 8.69a1 1 0 0 0 0-1.41L15.72 5.19a1 1 0 0 0-1.41 0L5.5 14.5M13.5 6.5l2 2' stroke-linecap='round' stroke-linejoin='round'/></svg><span class='visually-hidden'>编辑点位</span></button></div>
          <table class='table tags-table' style='font-size:.72rem;position:relative;'>
            <thead><tr>
              <th style='width:40%;position:relative;'>Tag <button type='button' class='filter-btn' id='filterBtn-name-${deviceId}' onclick='openFilterPop(${deviceId},"name");event.stopPropagation();' aria-label='过滤名称'><svg viewBox='0 0 24 24'><path d='M4 5h16L14 13v6l-4-2v-4z' stroke-linecap='round' stroke-linejoin='round'/></svg></button>
                <div class='filter-pop' data-device='${deviceId}' id='filterPop-name-${deviceId}' data-open='0'>
                  <input type='text' placeholder='包含...'/>
                  <div class='fp-actions'>
                    <button type='button' class='fp-apply' onclick='applyFilter(${deviceId},"name")'>确定</button>
                    <button type='button' class='fp-clear' onclick='clearFilter(${deviceId},"name")'>清除</button>
                  </div>
                </div>
              </th>
              <th style='width:30%;position:relative;'>地址 <button type='button' class='filter-btn' id='filterBtn-address-${deviceId}' onclick='openFilterPop(${deviceId},"address");event.stopPropagation();' aria-label='过滤地址'><svg viewBox='0 0 24 24'><path d='M4 5h16L14 13v6l-4-2v-4z' stroke-linecap='round' stroke-linejoin='round'/></svg></button>
                <div class='filter-pop' data-device='${deviceId}' id='filterPop-address-${deviceId}' data-open='0'>
                  <input type='text' placeholder='包含...'/>
                  <div class='fp-actions'>
                    <button type='button' class='fp-apply' onclick='applyFilter(${deviceId},"address")'>确定</button>
                    <button type='button' class='fp-clear' onclick='clearFilter(${deviceId},"address")'>清除</button>
                  </div>
                </div>
              </th>
              <th style='width:30%;position:relative;'>值 <button type='button' class='filter-btn' id='filterBtn-value-${deviceId}' onclick='openFilterPop(${deviceId},"value");event.stopPropagation();' aria-label='过滤数值'><svg viewBox='0 0 24 24'><path d='M4 5h16L14 13v6l-4-2v-4z' stroke-linecap='round' stroke-linejoin='round'/></svg></button>
                <div class='filter-pop' data-device='${deviceId}' id='filterPop-value-${deviceId}' data-open='0'>
                  <input type='text' placeholder='包含...'/>
                  <div class='fp-actions'>
                    <button type='button' class='fp-apply' onclick='applyFilter(${deviceId},"value")'>确定</button>
                    <button type='button' class='fp-clear' onclick='clearFilter(${deviceId},"value")'>清除</button>
                  </div>
                </div>
              </th>
            </tr></thead>
            <tbody id='tagBody-${deviceId}'><tr><td colspan='3' class='text-dim' style='padding:.35rem .3rem;'>加载中...</td></tr></tbody>
          </table>
          <div class='tag-pagination' id='tagPager-${deviceId}' style='display:none;'>
            <button type='button' class='btn-icon pager-prev' aria-label='上一页' onclick='changeTagPage(${deviceId},-1)'><svg viewBox='0 0 24 24'><path d='M14 6l-6 6 6 6' stroke-linecap='round' stroke-linejoin='round'/></svg></button>
            <span class='pager-info'>1 / 1</span>
            <button type='button' class='btn-icon pager-next' aria-label='下一页' onclick='changeTagPage(${deviceId},1)'><svg viewBox='0 0 24 24'><path d='M10 6l6 6-6 6' stroke-linecap='round' stroke-linejoin='round'/></svg></button>
          </div>`;
        grid.appendChild(card); renderDeviceTags(deviceId); }); id('lastRefresh').textContent='最后刷新: '+new Date().toLocaleTimeString(); updateDeviceCount(list.length); if(auto){ const st=id('autoStatus'); if(st){ st.classList.add('fetch'); setTimeout(()=>st.classList.remove('fetch'),280); } }
      }).catch(err=>console.error('刷新失败',err)); }

  function attachTagRowEvents(scope=document){ scope.querySelectorAll('.tag-row').forEach(tr=>{ tr.addEventListener('click',()=>{ const d=tr.getAttribute('data-device-id'); const t=tr.getAttribute('data-tag-id'); const n=tr.getAttribute('data-tag-name')||''; if(d&&t){ openDrawer(d,t,n); } }, { once:true }); }); }

  // Drawer logic (history + prediction)
  function openDrawer(did,tid,name){ stopDrawerAuto(); currentDeviceId=did; currentTagId=tid; currentTagName=name||''; id('drawerTitle').textContent=`${name||'标签'} 历史趋势`; const overlay=id('drawerOverlay'); overlay.style.display='block'; overlay.setAttribute('aria-hidden','false'); drawerOpen=true; loadHistory(); startDrawerAuto(); }
  function startDrawerAuto(){ stopDrawerAuto(); drawerInterval=setInterval(()=>refreshDrawerHistory(), DRAWER_REFRESH_MS); }
  function stopDrawerAuto(){ if(drawerInterval){ clearInterval(drawerInterval); drawerInterval=null; } }
  function refreshDrawerHistory(){ if(!drawerOpen||!currentDeviceId||!currentTagId) return; if(drawerFetchInFlight) return; drawerFetchInFlight=true; fetch(`/data/api/history/${currentDeviceId}/${currentTagId}`).then(r=>r.json()).then(list=>{ renderHistory(list); const tm=id('drawerUpdateTime'); if(tm) tm.textContent=new Date().toLocaleTimeString(); }).catch(()=>{}).finally(()=>{ drawerFetchInFlight=false; }); }
  function closeDrawer(){ const overlay=id('drawerOverlay'); overlay.style.display='none'; overlay.setAttribute('aria-hidden','true'); drawerOpen=false; prediction=null; chartMeta=null; stopDrawerAuto(); }
  function loadHistory(){ const body=id('drawerTableBody'); body.innerHTML='<tr><td colspan="2" style="padding:6px;" class="text-dim">加载中...</td></tr>'; fetch(`/data/api/history/${currentDeviceId}/${currentTagId}`).then(r=>r.json()).then(list=>{ renderHistory(list); }).catch(()=>{ body.innerHTML='<tr><td colspan="2" style="padding:6px;color:var(--color-danger);">加载失败</td></tr>'; }); }
  function renderHistory(entries){ const body=id('drawerTableBody'); if(!entries||!entries.length){ body.innerHTML='<tr><td colspan="2" style="padding:6px;" class="text-dim">暂无数据</td></tr>'; drawChart([]); return; } body.innerHTML=entries.map(e=>`<tr><td>${e.timestamp||''}</td><td>${e.value||''}</td></tr>`).join(''); drawChart(entries); }
  function drawChart(entries){ const canvas=id('drawerCanvas'); if(!canvas) return; resizeDrawerCanvas(canvas); const dCtx=canvas.getContext('2d'); const W=canvas.width,H=canvas.height; dCtx.clearRect(0,0,W,H); const pts=parseData(entries); const fpts=(prediction&&prediction.points)||[]; const hint=id('drawerHint'); if(!pts.length){ if(hint) hint.style.display=(entries&&entries.length)?'':'none'; return; } hint.style.display='none'; const all=fpts.length?pts.concat(fpts):pts; const tMin=Math.min(...all.map(p=>p.t)), tMax=Math.max(...all.map(p=>p.t)); let yMin=Math.min(...all.map(p=>p.y)), yMax=Math.max(...all.map(p=>p.y)); if(yMin===yMax){ yMin-=1; yMax+=1; } const pad=(yMax-yMin)*0.1; yMin-=pad; yMax+=pad; const L=54,R=18,T=14,B=36; dCtx.strokeStyle='rgba(0,191,255,0.3)'; dCtx.strokeRect(L,T,W-L-R,H-T-B); dCtx.font=`${11*(global.devicePixelRatio||1)}px monospace`; dCtx.textAlign='right'; dCtx.textBaseline='middle'; for(let i=0;i<=4;i++){ const yy=T+(H-T-B)*(i/4); const v=yMax-(yMax-yMin)*(i/4); dCtx.strokeStyle='rgba(0,191,255,0.15)'; dCtx.beginPath(); dCtx.moveTo(L,yy); dCtx.lineTo(W-R,yy); dCtx.stroke(); dCtx.fillStyle='#7fdcff'; dCtx.fillText(v.toFixed(2),L-6,yy);} dCtx.textAlign='center'; dCtx.textBaseline='top'; for(let i=0;i<=4;i++){ const ratio=(i/4); const xx=L+(W-L-R)*ratio; const tt=new Date(tMin+(tMax-tMin)*ratio); const label=`${tt.getHours().toString().padStart(2,'0')}:${tt.getMinutes().toString().padStart(2,'0')}:${tt.getSeconds().toString().padStart(2,'0')}`; dCtx.fillStyle='#7fdcff'; dCtx.fillText(label,xx,H-B+4);} dCtx.setLineDash([]); dCtx.strokeStyle='#00bfff'; dCtx.lineWidth=2; dCtx.beginPath(); for(let i=0;i<pts.length;i++){ const p=pts[i]; const xr=(tMax===tMin)?(i/(Math.max(pts.length-1,1))):((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; if(i===0)dCtx.moveTo(x,y); else dCtx.lineTo(x,y);} dCtx.stroke(); dCtx.fillStyle='#7fdcff'; for(const p of pts){ const xr=(tMax===tMin)?0:((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; dCtx.beginPath(); dCtx.arc(x,y,2.2*(global.devicePixelRatio||1),0,Math.PI*2); dCtx.fill(); } if(fpts.length){ dCtx.setLineDash([6,4]); dCtx.strokeStyle='#ffa500'; dCtx.beginPath(); for(let i=0;i<fpts.length;i++){ const p=fpts[i]; const xr=(tMax===tMin)?(i/(Math.max(fpts.length-1,1))):((p.t-tMin)/(tMax-tMin)); const x=L+(W-L-R)*xr; const yr=(p.y-yMin)/(yMax-yMin); const y=H-B-(H-T-B)*yr; if(i===0)dCtx.moveTo(x,y); else dCtx.lineTo(x,y);} dCtx.stroke(); dCtx.setLineDash([]);} chartMeta={tMin,tMax,yMin,yMax,L,R,T,B,W,H,pts,fpts}; updateAccuracy(); }
  function parseData(arr){ return C.parseData?C.parseData(arr):[]; }
  function resizeDrawerCanvas(canvas){ const ratio=Math.max(global.devicePixelRatio||1,1); const rect=canvas.getBoundingClientRect(); const tw=Math.floor(rect.width*ratio); const th=Math.floor(rect.height*ratio); if(canvas.width!==tw||canvas.height!==th){ canvas.width=tw; canvas.height=th; } }
  function updateAccuracy(){ const metricBox=id('metricBox'); if(!chartMeta||!prediction||!prediction.points){ metricBox.style.display='none'; return; } const pts=chartMeta.pts; const fpts=prediction.points; const map=new Map(); for(const p of pts) map.set(p.t,p.y); const matched=[]; for(const fp of fpts){ if(map.has(fp.t)) matched.push({pred:fp.y,actual:map.get(fp.t)}); } if(!matched.length){ metricBox.style.display='none'; return; } let se=0,ae=0; for(const m of matched){ const err=m.actual-m.pred; ae+=Math.abs(err); se+=err*err; } const n=matched.length; const mae=ae/n; const rmse=Math.sqrt(se/n); metricBox.innerHTML=`评估: <small>样本=${n}</small> | <small>MAE=${mae.toFixed(3)}</small> | <small>RMSE=${rmse.toFixed(3)}</small>`; metricBox.style.display=''; }
  function doPredictInDrawer(){ if(!currentDeviceId||!currentTagId) return; const rows=[...document.querySelectorAll('#drawerTableBody tr')].map(r=>({ timestamp:r.children[0]?.textContent, value:r.children[1]?.textContent })); const pts=parseData(rows); if(!pts.length){ alert('当前没有可预测的数值数据'); return; } const btn=id('predictBtn'); const st=id('predictStatus'); btn.disabled=true; st.style.display=''; fetch(`/data/api/predict/${currentDeviceId}/${currentTagId}`).then(r=>r.json()).then(data=>{ const raw=Array.isArray(data?.predictionPoints)?data.predictionPoints:[]; const tmp=[]; for(const pp of raw){ if(!pp||!pp.timestamp||!C.isNumeric?.(pp.value)) continue; const t=C.parseTs(pp.timestamp); if(isNaN(t)) continue; tmp.push({t,y:Number(pp.value)}); } tmp.sort((a,b)=>a.t-b.t); prediction=tmp.length?{points:tmp}:null; drawChart(rows); }).catch(()=>{}).finally(()=>{ btn.disabled=false; st.style.display='none'; }); }

  // Add device modal (dashboard variant asynchronous)
  function openAddDeviceModal(){ const m=id('addDeviceModal'); const input=id('devName'); m.style.display='flex'; m.setAttribute('aria-hidden','false'); setTimeout(()=>input&&input.focus(),10); }
  function closeAddDeviceModal(){ const m=id('addDeviceModal'); if(!m) return; m.style.display='none'; m.setAttribute('aria-hidden','true'); id('addDevMsg').textContent=''; }
  function submitAddDevice(){ const name=id('devName').value.trim(); const protocol=id('devProtocol').value.trim(); const conn=id('devConn').value.trim(); const msg=id('addDevMsg'); if(!name){ msg.textContent='名称不能为空'; return false; } if(!conn){ msg.textContent='连接字符串不能为空'; return false; } fetch('/data/api/devices',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,protocol,connectionString:conn})}).then(r=>r.json()).then(resp=>{ if(resp.success){ msg.textContent='保存成功'; msg.classList.remove('text-danger'); setTimeout(()=>{ closeAddDeviceModal(); fetchAndRender(); },500);} else { msg.textContent=resp.message||'保存失败'; msg.classList.add('text-danger'); } }).catch(err=>{ msg.textContent='异常: '+err; msg.classList.add('text-danger'); }); return false; }

  // Namespace modal
  let currentNsDeviceId=null; function openNamespaces(deviceId){ currentNsDeviceId=deviceId; const modal=id('namespaceModal'); modal.style.display='flex'; const ul=id('nsList'); ul.innerHTML='<li class="ns-item" style="cursor:default;opacity:.7;">加载中...</li>'; id('nsTagBody').innerHTML='<tr><td colspan="4" class="ns-empty">请选择左侧 Namespace</td></tr>'; id('nsStatus').textContent=''; id('nsSelectedLabel').textContent=''; fetch(`/data/api/${deviceId}/namespaces`).then(r=>r.json()).then(list=>{ ul.innerHTML=''; if(!list||!list.length){ ul.innerHTML='<li class="ns-item" style="cursor:default;color:var(--color-warn);">无数据或读取失败</li>'; return; } list.forEach(ns=>{ const li=document.createElement('li'); li.className='ns-item'; li.innerHTML=`<span style="flex:1;">#${ns.index} ${ns.uri||''}</span><span style='font-size:0.55rem;color:var(--color-text-dim);'>查看</span>`; li.addEventListener('click',()=>loadNamespaceTags(deviceId,ns.index,li,ns.uri)); ul.appendChild(li); }); }).catch(err=>{ ul.innerHTML='<li class="ns-item" style="cursor:default;color:var(--color-danger);">加载失败 '+err+'</li>'; }); }
  function closeNsModal(){ id('namespaceModal').style.display='none'; }
  function loadNamespaceTags(deviceId,nsIndex,li,uri){
    document.querySelectorAll('#nsList .ns-item').forEach(x=>x.classList.remove('active')); li.classList.add('active');
    id('nsSelectedLabel').textContent=`已选: #${nsIndex}${uri?(' '+uri):''}`;
    const body=id('nsTagBody'); body.innerHTML=`<tr><td colspan='4' class='ns-empty'>加载中...</td></tr>`;
    fetch(`/data/api/${deviceId}/namespaces/${nsIndex}/tags`).then(r=>r.json()).then(tags=>{
      if(!tags||!tags.length){ body.innerHTML=`<tr><td colspan='4' class='ns-empty'>无数据</td></tr>`; id('nsStatus').textContent=''; return; }
      body.innerHTML=tags.map(t=>{ const nm=C.escHtml?C.escHtml(t.name||''): (t.name||''); const addr=C.escHtml?C.escHtml(t.address||''):(t.address||''); const val=(t.value==null)?'':(C.escHtml?C.escHtml(t.value):t.value);
        return `<tr><td>${nm}</td><td>${addr}</td><td>${val}</td><td class='action-cell'><button type='button' class='btn-icon ok add-tag-btn' title='添加' aria-label='添加' onclick='quickAddTag(${deviceId},"${nm.replace(/"/g,'&quot;')}","${addr.replace(/"/g,'&quot;')}",this)'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M12 5v14M5 12h14' stroke-linecap='round'/></svg><span class='visually-hidden'>添加</span></button></td></tr>`; }).join('');
      id('nsStatus').textContent=`已加载 ${tags.length} 条`;
    }).catch(err=>{ body.innerHTML=`<tr><td colspan='4' class='ns-empty' style='color:var(--color-danger);'>加载失败 ${err}</td></tr>`; id('nsStatus').textContent=''; });
  }
  function showNsMessage(msg,ok){ const status=id('nsStatus'); status.innerHTML=`<span class='${ok?'msg-ok':'msg-err'}'>${msg}</span>`; setTimeout(()=>{ if(status.innerHTML.includes(msg)) status.innerHTML=''; },4000); }
  function quickAddTag(deviceId,name,address,btn){
    if(!btn||btn.disabled) return; btn.disabled=true; const label=btn.querySelector('.visually-hidden'); const svg=btn.querySelector('svg');
    btn.classList.add('loading'); if(label){ label.textContent='添加中...'; } btn.title='添加中...'; btn.setAttribute('aria-label','添加中...');
    fetch(`/data/api/${deviceId}/tags`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,address})}).then(r=>r.json()).then(resp=>{
      btn.classList.remove('loading'); if(resp.success){ if(label) label.textContent='已添加'; btn.title='已添加'; btn.setAttribute('aria-label','已添加'); btn.classList.add('added'); btn.disabled=true; fetchAndRender(); } else { if(label) label.textContent='添加'; btn.title='添加'; btn.setAttribute('aria-label','添加'); btn.disabled=false; alert(resp.message||'添加失败'); }
    }).catch(err=>{ btn.classList.remove('loading'); if(label) label.textContent='添加'; btn.title='添加'; btn.setAttribute('aria-label','添加'); btn.disabled=false; alert('异常: '+err); });
  }

  // Edit tags modal
  let currentEditTagDeviceId=null; function openEditTags(deviceId){ currentEditTagDeviceId=deviceId; id('editTagsMsg').textContent=''; const m=id('editTagsModal'); m.style.display='flex'; m.setAttribute('aria-hidden','false'); loadEditTags(); }
  function closeEditTagsModal(){ const m=id('editTagsModal'); m.style.display='none'; m.setAttribute('aria-hidden','true'); }
  function loadEditTags(){ const body=id('editTagsBody'); body.innerHTML='<tr><td colspan="3" class="text-dim" style="padding:.4rem;">加载中...</td></tr>'; fetch(`/data/api/${currentEditTagDeviceId}/tags`).then(r=>r.json()).then(list=>{ if(!list||!list.length){ body.innerHTML='<tr><td colspan="3" class="text-warn" style="padding:.4rem;">暂无点位</td></tr>'; return; } body.innerHTML=list.map(t=>`<tr data-tag-id='${t.id}'><td><input class='et-name' value='${escHtml(t.name||'')}' maxlength='120' style='width:100%;'></td><td><input class='et-address' value='${escHtml(t.address||'')}' maxlength='500' style='width:100%;'></td><td style='display:flex;gap:.25rem;'><button type='button' onclick='saveTag(this)' class='btn-icon' title='保存' aria-label='保存'><svg viewBox='0 0 24 24'><path d='M5 13l4 4L19 7' stroke-linecap='round' stroke-linejoin='round'/></svg></button><button type='button' onclick='deleteTagRow(this)' class='btn-icon danger' title='删除点位' aria-label='删除点位'><svg viewBox='0 0 24 24'><path d='M6 7h12M10 7V5h4v2m-7 0v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V7M9 11v6M15 11v6' stroke-linecap='round' stroke-linejoin='round'/></svg></button></td></tr>`).join(''); }).catch(err=>{ body.innerHTML=`<tr><td colspan='3' style='color:var(--color-danger);padding:.4rem;'>加载失败 ${err}</td></tr>`; }); }
  function setEditMsg(msg,ok){ const box=id('editTagsMsg'); box.textContent=msg; box.style.color=ok?'var(--color-text-dim)':'var(--color-danger)'; if(ok) setTimeout(()=>{ if(box.textContent===msg) box.textContent=''; },2500); }
  function saveTag(btn){ const tr=btn.closest('tr'); if(!tr) return; const idAttr=tr.getAttribute('data-tag-id'); const name=tr.querySelector('.et-name').value.trim(); const address=tr.querySelector('.et-address').value.trim(); if(!address){ setEditMsg('地址不能为空',false); return; } btn.disabled=true; btn.classList.add('loading'); fetch(`/data/api/${currentEditTagDeviceId}/tags/${idAttr}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,address})}).then(r=>r.json()).then(resp=>{ if(resp.success){ setEditMsg('保存成功',true); fetchAndRender(); setTimeout(()=>{ btn.disabled=false; btn.classList.remove('loading'); },600);} else { btn.disabled=false; btn.classList.remove('loading'); setEditMsg(resp.message||'保存失败',false); } }).catch(err=>{ btn.disabled=false; btn.classList.remove('loading'); setEditMsg('异常:'+err,false); }); }
  function deleteTagRow(btn){ const tr=btn.closest('tr'); if(!tr) return; const idAttr=tr.getAttribute('data-tag-id'); if(!confirm('确认删除该点位?')) return; btn.disabled=true; btn.classList.add('loading'); fetch(`/data/api/${currentEditTagDeviceId}/tags/${idAttr}`,{method:'DELETE'}).then(r=>r.json()).then(resp=>{ if(resp.success){ tr.remove(); setEditMsg('删除成功',true); fetchAndRender(); } else { btn.disabled=false; btn.classList.remove('loading'); setEditMsg(resp.message||'删除失败',false); } }).catch(err=>{ btn.disabled=false; btn.classList.remove('loading'); setEditMsg('异常:'+err,false); }); }

  function deleteDevice(deviceId,btn){ if(!confirm('确认删除该设备及其所有点位?')) return; btn.disabled=true; btn.classList.add('loading'); fetch(`/data/api/devices/${deviceId}`,{method:'DELETE'}).then(r=>r.json()).then(resp=>{ if(resp.success){ if(currentNsDeviceId==deviceId) closeNsModal(); if(currentEditTagDeviceId==deviceId) closeEditTagsModal(); fetchAndRender(); } else { alert(resp.message||'删除失败'); btn.disabled=false; btn.classList.remove('loading'); } }).catch(err=>{ alert('异常: '+err); btn.disabled=false; btn.classList.remove('loading'); }); }

  function updateDeviceCount(n){ const box=id('deviceCount'); if(box) box.textContent='设备: '+n; }

  function openEditDevice(id){ // open in-page modal instead of redirect
    if(!id) return; const modal=document.getElementById('editDeviceModal'); if(!modal) return;
    const card=document.querySelector(`.card[data-device-id='${id}']`);
    const nameInput=document.getElementById('editDevName');
    const idInput=document.getElementById('editDevId');
    const connInput=document.getElementById('editDevConn');
    const protoSelect=document.getElementById('editDevProtocol');
    const msg=document.getElementById('editDevMsg');
    msg.textContent=''; msg.style.color='';
    if(card){
      idInput.value=id;
      nameInput.value=card.dataset.deviceName||'';
      protoSelect.value=(card.dataset.deviceProtocol||'opcua');
      connInput.value=card.dataset.deviceConn||'';
    } else {
      // fallback fetch latest snapshot list then reopen
      fetch('/data/api/latest').then(r=>r.json()).then(list=>{ const dev=list.find(d=>String(d.deviceId)===String(id)); if(dev){ idInput.value=id; nameInput.value=dev.deviceName||''; protoSelect.value=dev.protocol||'opcua'; connInput.value=dev.connectionString||''; }}).catch(()=>{});
    }
    modal.style.display='flex'; modal.setAttribute('aria-hidden','false'); setTimeout(()=>nameInput&&nameInput.focus(),10);
  }
  function closeEditDeviceModal(){ const modal=document.getElementById('editDeviceModal'); if(!modal) return false; modal.style.display='none'; modal.setAttribute('aria-hidden','true'); return false; }
  function submitEditDevice(){ const idVal=document.getElementById('editDevId').value; const name=document.getElementById('editDevName').value.trim(); const protocol=document.getElementById('editDevProtocol').value.trim()||'opcua'; const conn=document.getElementById('editDevConn').value.trim(); const msg=document.getElementById('editDevMsg'); if(!name){ msg.textContent='名称不能为空'; msg.style.color='var(--color-danger)'; return false;} if(!conn){ msg.textContent='连接字符串不能为空'; msg.style.color='var(--color-danger)'; return false; }
    msg.textContent='保存中...'; msg.style.color='';
    fetch(`/data/api/devices/${idVal}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,protocol,connectionString:conn})}).then(r=>r.json()).then(resp=>{ if(resp.success){ msg.textContent='保存成功'; msg.style.color=''; setTimeout(()=>{ closeEditDeviceModal(); fetchAndRender(); },400); } else { msg.textContent=resp.message||'保存失败'; msg.style.color='var(--color-danger)'; } }).catch(err=>{ msg.textContent='异常: '+err; msg.style.color='var(--color-danger)'; });
    return false; }
  let editMode=false; function toggleEditMode(){ editMode=!editMode; const body=document.body; const sw=document.getElementById('editModeSwitch'); const sr=document.getElementById('editModeSr'); if(editMode){ body.classList.add('edit-mode'); sw?.classList.add('on'); sw?.setAttribute('aria-checked','true'); if(sw){ sw.title='关闭编辑模式'; sw.setAttribute('aria-label','关闭编辑模式'); } if(sr) sr.textContent='关闭编辑模式'; } else { body.classList.remove('edit-mode'); sw?.classList.remove('on'); sw?.setAttribute('aria-checked','false'); if(sw){ sw.title='开启编辑模式'; sw.setAttribute('aria-label','开启编辑模式'); } if(sr) sr.textContent='开启编辑模式'; }
    // 当关闭编辑模式时关闭所有展开的过滤弹层
    if(!editMode){ document.querySelectorAll('.filter-pop[data-open="1"]').forEach(p=>{ p.setAttribute('data-open','0'); p.style.display='none'; }); }
  }
  // Export functions to global for HTML inline handlers compatibility
  Object.assign(global, { toggleAuto, openDrawer, closeDrawer, doPredictInDrawer, openAddDeviceModal, closeAddDeviceModal, submitAddDevice, openNamespaces, closeNsModal, quickAddTag, openEditTags, closeEditTagsModal, saveTag, deleteTagRow, deleteDevice, openEditDevice, closeEditDeviceModal, submitEditDevice, openFilterPop, applyFilter, clearFilter, changeTagPage, toggleEditMode });

  function bindGlobalListeners(){ const drawerOverlay=id('drawerOverlay'); drawerOverlay?.addEventListener('click', e=>{ if(e.target===drawerOverlay) closeDrawer(); }); document.addEventListener('keydown', e=>{ if(e.key==='Escape'){ if(drawerOpen) closeDrawer(); // close filter pops
      document.querySelectorAll('.filter-pop[data-open="1"]').forEach(p=>{ p.style.display='none'; p.setAttribute('data-open','0'); }); } }); id('addDeviceModal')?.addEventListener('click', e=>{ if(e.target===id('addDeviceModal')) closeAddDeviceModal(); }); id('editTagsModal')?.addEventListener('click', e=>{ if(e.target===id('editTagsModal')) closeEditTagsModal(); }); id('editDeviceModal')?.addEventListener('click', e=>{ if(e.target===id('editDeviceModal')) closeEditDeviceModal(); }); document.addEventListener('keydown', e=>{ if(e.key==='Escape'){ closeNsModal(); closeEditDeviceModal(); } });
    // click outside filter pops
    document.addEventListener('click', e=>{
      if(e.target.closest('.filter-pop') || e.target.closest('.filter-btn')) return;
      document.querySelectorAll('.filter-pop[data-open="1"]').forEach(p=>{ p.style.display='none'; p.setAttribute('data-open','0'); });
    }); }

  function start(){ bindGlobalListeners(); fetchAndRender(); }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', start); else start();
})(window);
