// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

/**
 * The light controller page: one self-contained HTML file (no downloads), held sideways. [PhonePads]
 * serves it when no Compose controller ([PhonePadController]) is bundled, and always at `/lite` (the
 * Compose page sends browsers without WebAssembly GC there). Two layouts switched by the game ([PhoneLayout]):
 *
 * - DRIVE: a floating stick on the left half (touch anywhere, slide), BRAKE / DRIFT and BOOST (hold
 *   for SURGE) on the right; AUTO GAS (on by default; off shows a GAS button), TILT (where the phone
 *   allows motion sensors), RESET (hold) and PAUSE along the top.
 * - MENU: a D-pad, Ⓐ Ⓑ Ⓧ and START.
 *
 * It reconnects by itself (a locked phone comes back as the same pad), keeps an id in local storage,
 * measures the round trip once a second and vibrates on `vib`.
 */
object PhonePadPage {
    val HTML: String = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-capable" content="yes">
<meta name="theme-color" content="#0b0820">
<title>Controller</title>
<style>
:root{--ink:#0b0820;--plate:#15112e;--line:#2c2550;--text:#f4f2ff;--dim:#9aa3c7;--me:#8a8fa8}
*{box-sizing:border-box;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:var(--ink);color:var(--text);font:800 16px/1.1 system-ui,-apple-system,Roboto,sans-serif;overflow:hidden;touch-action:none;overscroll-behavior:none}
#top{position:fixed;left:0;right:0;top:0;height:46px;display:flex;align-items:center;gap:10px;padding:0 max(12px,env(safe-area-inset-left));border-bottom:3px solid var(--me);background:var(--plate)}
#tag{background:var(--me);color:var(--ink);border-radius:8px;padding:4px 10px;font-size:20px;min-width:44px;text-align:center}
#status{flex:1;font-size:16px;letter-spacing:1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#ms{color:var(--dim);font-size:13px}
#dot{width:10px;height:10px;border-radius:50%;background:#ff5a4a}
#dot.on{background:#6dff9e}
.pad{position:fixed;left:0;right:0;top:46px;bottom:0;display:none}
body.drive #drive,body.menu #menu{display:flex}
#steer{position:relative;flex:1;border-right:2px solid var(--line)}
#steer .hint{position:absolute;left:0;right:0;bottom:18px;text-align:center;color:var(--dim);font-size:14px;letter-spacing:2px}
#knob{position:absolute;width:84px;height:84px;margin:-42px 0 0 -42px;border-radius:50%;border:4px solid var(--me);background:rgba(255,255,255,.08);display:none;pointer-events:none}
#base{position:absolute;height:6px;margin-top:-3px;background:var(--line);border-radius:3px;display:none;pointer-events:none}
#right{flex:1.15;display:flex;flex-direction:column;padding:10px;gap:10px}
.row{display:flex;gap:10px}
.small{height:52px}
.big{flex:1}
button{flex:1;border:0;border-radius:16px;color:var(--ink);font:900 18px system-ui,-apple-system,Roboto,sans-serif;letter-spacing:1px;touch-action:none}
button small{display:block;font-size:11px;font-weight:800;opacity:.75;margin-top:4px}
button.on,button:active,button.down{filter:brightness(1.35);transform:scale(.97)}
.grey{background:#dfe3f5}.green{background:#3ddc84}.red{background:#ff5a5a}.blue{background:#3d9bff}.yellow{background:#ffd23a}.violet{background:#c04fff;color:#fff}
.toggle{background:#3a3360;color:var(--dim)}.toggle.on{background:#6dff9e;color:var(--ink)}
.hidden{display:none}
#menu{align-items:center;justify-content:space-around;padding:12px}
#dpad{display:grid;grid-template-columns:repeat(3,92px);grid-template-rows:repeat(3,92px);gap:8px}
#dpad button{background:#dfe3f5;font-size:30px}
#face{display:grid;grid-template-columns:repeat(3,96px);grid-template-rows:60px 96px 96px;gap:12px;align-items:center;justify-items:center}
#face button{width:96px;height:96px;border-radius:50%;font-size:34px;flex:none}
#face .start{grid-column:1/4;width:200px;height:52px;border-radius:26px;font-size:18px;background:#dfe3f5}
#rotate{position:fixed;inset:0;display:none;align-items:center;justify-content:center;text-align:center;background:var(--ink);font-size:24px;padding:30px;z-index:9}
@media (orientation:portrait){#rotate{display:flex}}
</style></head>
<body class="menu">
<div id="top"><span id="dot"></span><span id="tag">--</span><span id="status">CONNECTING…</span><span id="ms"></span></div>
<div id="drive" class="pad">
  <div id="steer"><div id="base"></div><div id="knob"></div><div class="hint">TOUCH AND SLIDE TO STEER</div></div>
  <div id="right">
    <div class="row small">
      <button class="toggle on" id="auto">AUTO GAS</button>
      <button class="toggle" id="tilt">TILT</button>
      <button class="yellow" data-b="8">RESET<small>HOLD</small></button>
      <button class="grey" data-b="16">PAUSE</button>
    </div>
    <div class="row big">
      <button class="red" data-k="brake">BRAKE<small>+ STEER: DRIFT</small></button>
      <button class="green hidden" data-k="gas" id="gas">GAS</button>
      <button class="violet" data-b="1">BOOST<small>HOLD: SURGE</small></button>
    </div>
  </div>
</div>
<div id="menu" class="pad">
  <div id="dpad">
    <span></span><button data-b="32">▲</button><span></span>
    <button data-b="128">◀</button><span></span><button data-b="256">▶</button>
    <span></span><button data-b="64">▼</button><span></span>
  </div>
  <div id="face">
    <button class="start" data-b="16">START</button>
    <span></span><button class="yellow" data-b="8">Y</button><span></span>
    <button class="blue" data-b="4">X</button><button class="red" data-b="2">B</button><button class="green" data-b="1">A</button>
  </div>
</div>
<div id="rotate">Turn your phone sideways</div>
<script>
(function(){
var id;
try{id=localStorage.getItem('jfpad');if(!id){id=Math.random().toString(36).slice(2,12);localStorage.setItem('jfpad',id)}}catch(e){id=Math.random().toString(36).slice(2,12)}
var ws=null,layout='MENU',auto=true,tiltOn=false,tiltSteer=0;
var steer=0,held={},stickId=null,stickX=0,last='';
var body=document.body,knob=document.getElementById('knob'),base=document.getElementById('base');
function buzz(ms){try{if(navigator.vibrate)navigator.vibrate(ms)}catch(e){}}
function state(){
  var mask=0,brake=0,gasHeld=0,k;
  for(k in held){var h=held[k];if(h.b)mask|=h.b;if(h.k==='brake')brake=1;if(h.k==='gas')gasHeld=1}
  var drive=layout==='DRIVE';
  var s=drive?(tiltOn?tiltSteer:steer):0;
  var gas=drive?(auto?(brake?0:1):gasHeld):0;
  if(!drive)brake=0;
  return 'in '+s.toFixed(3)+' '+gas+' '+brake+' '+mask;
}
function send(force){var m=state();if(ws&&ws.readyState===1&&(force||m!==last)){ws.send(m);last=m}}
setInterval(function(){send(true)},200);
setInterval(function(){if(ws&&ws.readyState===1)ws.send('ping '+performance.now().toFixed(1))},1000);
var opened=false,fails=0;
function connect(){
  var secure=location.protocol==='https:';
  ws=new WebSocket((secure?'wss://':'ws://')+location.host+'/ws');
  ws.onopen=function(){opened=true;document.getElementById('dot').className='on';setStatus('CONNECTED');ws.send('hello '+id);send(true)};
  ws.onclose=function(){
    // Safari opens no WebSocket to a self-signed certificate: use the plain-HTTP twin on the next port.
    if(secure&&!opened&&++fails>=2){location.replace('http://'+location.hostname+':'+(parseInt(location.port||'443',10)+1)+location.pathname);return}
    document.getElementById('dot').className='';setStatus('RECONNECTING…');setTimeout(connect,1000)};
  ws.onerror=function(){try{ws.close()}catch(e){}};
  ws.onmessage=function(e){
    var m=String(e.data),p=m.split(' ');
    if(p[0]==='pong'){var rtt=Math.round(performance.now()-parseFloat(p[1]));document.getElementById('ms').textContent=rtt+' ms';ws.send('rtt '+rtt)}
    else if(p[0]==='vib'){buzz(parseInt(p[1],10)||20)}
    else if(p[0]==='info'){
      layout=p[1]==='DRIVE'?'DRIVE':'MENU';body.className=layout==='DRIVE'?'drive':'menu';
      document.documentElement.style.setProperty('--me','#'+p[2]);
      document.getElementById('tag').textContent=p[3]==='-'?'--':p[3].replace(/_/g,' ');
      setStatus(p.slice(4).join(' '));
      if(layout!=='DRIVE'){stickId=null;steer=0;knob.style.display=base.style.display='none'}
      send(true);
    }
  };
}
function setStatus(t){document.getElementById('status').textContent=t||''}
function key(el){return el&&el.closest?el.closest('button[data-b],button[data-k]'):null}
document.addEventListener('pointerdown',function(e){
  if(document.documentElement.requestFullscreen&&!document.fullscreenElement){document.documentElement.requestFullscreen().then(function(){try{screen.orientation.lock('landscape')}catch(x){}}).catch(function(){})}
  var b=key(e.target);
  if(b){e.preventDefault();held[e.pointerId]={b:parseInt(b.getAttribute('data-b')||'0',10),k:b.getAttribute('data-k'),el:b};b.classList.add('down');buzz(8);send();return}
  if(layout==='DRIVE'&&!tiltOn&&stickId===null&&e.target.closest&&e.target.closest('#steer')){
    var r=document.getElementById('steer').getBoundingClientRect();
    stickId=e.pointerId;stickX=e.clientX;
    knob.style.left=(e.clientX-r.left)+'px';knob.style.top=(e.clientY-r.top)+'px';knob.style.display='block';
    base.style.top=(e.clientY-r.top)+'px';base.style.left=(e.clientX-r.left-radius())+'px';base.style.width=(radius()*2)+'px';base.style.display='block';
  }
},{passive:false});
function radius(){return Math.max(50,Math.min(110,document.getElementById('steer').clientWidth*0.22))}
document.addEventListener('pointermove',function(e){
  if(e.pointerId!==stickId)return;
  var d=Math.max(-1,Math.min(1,(e.clientX-stickX)/radius()));
  steer=d;var r=document.getElementById('steer').getBoundingClientRect();
  knob.style.left=(stickX-r.left+d*radius())+'px';send();
});
function release(e){
  if(e.pointerId===stickId){stickId=null;steer=0;knob.style.display=base.style.display='none';send()}
  var h=held[e.pointerId];if(h){h.el.classList.remove('down');delete held[e.pointerId];send()}
}
document.addEventListener('pointerup',release);document.addEventListener('pointercancel',release);
document.addEventListener('contextmenu',function(e){e.preventDefault()});
document.addEventListener('touchmove',function(e){e.preventDefault()},{passive:false});
document.getElementById('auto').addEventListener('click',function(){
  auto=!auto;this.classList.toggle('on',auto);document.getElementById('gas').classList.toggle('hidden',auto);send(true)});
var tiltBtn=document.getElementById('tilt');
if(!('DeviceOrientationEvent' in window)||!window.isSecureContext)tiltBtn.classList.add('hidden');
tiltBtn.addEventListener('click',function(){
  function on(){tiltOn=!tiltOn;tiltBtn.classList.toggle('on',tiltOn);document.querySelector('#steer .hint').textContent=tiltOn?'TILT TO STEER':'TOUCH AND SLIDE TO STEER';send(true)}
  if(!tiltOn&&typeof DeviceOrientationEvent.requestPermission==='function'){
    DeviceOrientationEvent.requestPermission().then(function(r){if(r==='granted')on();else tiltBtn.textContent='NO TILT'}).catch(function(){tiltBtn.textContent='NO TILT'})
  }else on();
});
window.addEventListener('deviceorientation',function(e){
  if(!tiltOn||e.beta===null)return;
  var a=(screen.orientation&&screen.orientation.angle!==undefined)?screen.orientation.angle:(window.orientation||0);
  var v=a===90?e.beta:(a===270||a===-90)?-e.beta:e.gamma;
  tiltSteer=Math.max(-1,Math.min(1,v/28));send();
});
connect();
})();
</script>
</body></html>"""
}
