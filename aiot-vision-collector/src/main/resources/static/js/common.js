/* Common utility functions shared across pages (no build tool; attach to window) */
(function(global){
  'use strict';
  function escHtml(str){
    if(str==null) return '';
    return String(str)
      .replace(/&/g,'&amp;')
      .replace(/</g,'&lt;')
      .replace(/>/g,'&gt;')
      .replace(/"/g,'&quot;')
      .replace(/'/g,'&#39;');
  }
  function isNumeric(x){
    if(x==null) return false;
    const n = Number(String(x).replace(/,/g,''));
    return !isNaN(n) && isFinite(n);
  }
  function parseTs(s){
    if(!s) return NaN;
    s = String(s).trim();
    if (s.length >= 19 && s.charAt(10) === ' ') {
      const core = s.substring(0,19);
      s = core.substring(0,10)+'T'+core.substring(11);
    }
    let t = Date.parse(s);
    if(!isNaN(t)) return t;
    try { t = new Date(s.replace(/-/g,'/')).getTime(); } catch(e){ t = NaN; }
    return t;
  }
  function parseData(arr){
    const pts=[]; if(!Array.isArray(arr)) return pts;
    for(const e of arr){
      const t=parseTs(e.timestamp); if(isNaN(t)) continue;
      const v=e.value; if(!isNumeric(v)) continue;
      const y=Number(String(v).replace(/,/g,''));
      if(!isNaN(y)&&isFinite(y)) pts.push({t,y});
    }
    return pts;
  }
  function inferIntervalMs(pts){
    if(!pts || pts.length<2) return 0;
    const diffs=[]; for(let i=1;i<pts.length;i++){ const d=pts[i].t-pts[i-1].t; if(d>0) diffs.push(d);} if(!diffs.length) return 0;
    diffs.sort((a,b)=>a-b); const mid=Math.floor(diffs.length/2);
    return diffs.length % 2 ? diffs[mid] : Math.floor((diffs[mid-1]+diffs[mid])/2);
  }
  // Lightweight fetch helpers
  function apiGet(url){ return fetch(url,{cache:'no-store'}).then(r=>r.json()); }
  function apiSend(method,url,body){
    return fetch(url,{method,headers:{'Content-Type':'application/json'},body: body?JSON.stringify(body):undefined}).then(r=>r.json());
  }
  global.AiotCommon = { escHtml, isNumeric, parseTs, parseData, inferIntervalMs, apiGet, apiSend };
})(window);

