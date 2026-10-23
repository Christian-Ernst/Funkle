const { chromium } = require('playwright'); const fs=require('fs');
const icon=fs.readFileSync(__dirname+'/icon.svg','utf8').replace(/<svg [^>]*>/, m=>m.replace(/width="\d+" height="\d+"/,'')).replace('<svg ','<svg id="ic" ');
const font='@font-face{font-family:Syne;font-weight:800;src:url(data:font/woff2;base64,'+fs.readFileSync(__dirname+'/syne800.woff2').toString('base64')+') format("woff2")}';
const bg='radial-gradient(120% 140% at 0% 0%, rgba(255,46,140,.16), transparent 55%), radial-gradient(100% 120% at 100% 100%, rgba(46,200,255,.14), transparent 55%), #07060c';
const grad='linear-gradient(90deg,#ff2e8c,#b07cff 55%,#2ec8ff)';
function word(size,color){return `<div style="font:800 ${size}px/1 Syne;letter-spacing:-0.03em;background:${color};-webkit-background-clip:text;background-clip:text;color:transparent;padding-right:.05em">Funkle</div>`;}
const pages=[
 {out:'funkle-wordmark.png',w:2000,h:560,bgc:'transparent',html:`<div style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">${word(400,grad)}</div>`},
 {out:'funkle-wordmark-white.png',w:2000,h:560,bgc:'transparent',html:`<div style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">${word(400,'#fff')}</div>`},
 {out:'funkle-lockup.png',w:1200,h:630,bgc:bg,html:`<div style="display:flex;align-items:center;justify-content:center;gap:48px;width:100%;height:100%"><div style="flex:0 0 300px;width:300px;height:300px;border-radius:66px;overflow:hidden;box-shadow:0 30px 80px rgba(176,124,255,.25)">${icon}</div><div>${word(140,grad)}<div style="font:500 34px/1.3 Figtree,system-ui,sans-serif;color:#a49fbd;margin-top:18px">Your music, turned into light.</div></div></div>`},
 {out:'funkle-feature-1024x500.png',w:1024,h:500,bgc:bg,html:`<div style="display:flex;align-items:center;justify-content:center;gap:40px;width:100%;height:100%"><div style="flex:0 0 250px;width:250px;height:250px;border-radius:56px;overflow:hidden">${icon}</div><div>${word(112,grad)}<div style="font:500 28px/1.3 Figtree,system-ui,sans-serif;color:#a49fbd;margin-top:14px">Your music, turned into light.</div></div></div>`},
 {out:'funkle-icon-text.png',w:1080,h:1080,bgc:'#07060c',html:`<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;width:100%;height:100%"><div style="width:600px;height:600px;border-radius:132px;overflow:hidden">${icon}</div>${word(170,grad)}</div>`},
];
(async()=>{const b=await chromium.launch();
for(const p of pages){const pg=await b.newPage({viewport:{width:p.w,height:p.h},deviceScaleFactor:1});
await pg.setContent(`<html><head><style>${font} html,body{margin:0;width:100%;height:100%;background:${p.bgc}} #ic{width:100%;height:100%;display:block}</style></head><body>${p.html}</body></html>`);
await pg.evaluate(()=>document.fonts.ready); await pg.screenshot({path:__dirname+'/'+p.out,omitBackground:p.bgc==='transparent'}); console.log(p.out);}
await b.close();})();
