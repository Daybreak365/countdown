'use strict';
const $=id=>document.getElementById(id);
const preview={settings:{memoryMb:4096},account:null,busy:false,running:false,authPending:false,configured:false};
const previewError=async()=>({ok:false,error:'Windows 실행 파일에서 사용할 수 있습니다.'});
const api=window.launcher||{getState:async()=>({ok:true,value:preview}),saveSettings:async s=>({ok:true,value:{...preview,settings:s}}),run:previewError,logout:previewError,cancel:previewError,avatar:previewError,copyLoginCode:previewError,window:previewError,onProgress:()=>{},onState:()=>{},onAuth:()=>{}};
let state,avatarId,dirty=false,dialogAction,dialogReturnFocus,toastTimer,starting=false;
async function call(method,...args){const r=await api[method](...args);if(!r.ok)throw new Error(r.error);return r.value;}
function toast(message){$('toast').textContent=message;$('toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('show'),3600);}
function showDialog(title,body,action='',fn,returnFocus=document.activeElement){$('dialogTitle').textContent=title;$('dialogBody').textContent=body;$('dialogAction').textContent=action;$('dialogAction').hidden=!action;dialogAction=fn;dialogReturnFocus=returnFocus;if(!$('infoDialog').open)$('infoDialog').showModal();}
function navigate(page){document.querySelectorAll('.page').forEach(e=>e.classList.toggle('visible',e.id==='page-'+page));document.querySelectorAll('[data-page]').forEach(e=>{const active=e.dataset.page===page;e.classList.toggle('active',active);if(active)e.setAttribute('aria-current','page');else e.removeAttribute('aria-current');});}
function status(message,type='wait',details=''){$('dockStatus').hidden=message==='대기 중';$('statusText').textContent=message;$('statusDot').className=type;$('progressDetail').textContent=details;}
// Shows the signed-in player's skin face; on sign-out or any failure the default icon stays.
async function showAvatar(id){if(id===avatarId)return;avatarId=id;const box=$('avatar');box.classList.remove('has-face');box.querySelectorAll('img').forEach(e=>e.remove());if(!id||!api.avatar)return;try{const parts=await call('avatar');if(avatarId!==id)return;const images=await Promise.all([parts.face,parts.hat].filter(Boolean).map(src=>new Promise((resolve,reject)=>{const img=new Image();img.alt='';img.onload=()=>resolve(img);img.onerror=reject;img.src=src;})));if(avatarId!==id)return;box.append(...images);box.classList.add('has-face');}catch{}}
function applyState(s){state=s;showAvatar(s.account?.id);const busy=s.busy||starting;$('accountName').textContent=s.account?.name||'Microsoft 계정';$('accountHint').textContent=s.authPending?'로그인 중':s.account?'로그인됨':'로그인';$('accountButton').disabled=busy||s.running;$('playButton').disabled=busy||s.running;$('playLabel').textContent=s.authPending?'로그인 중':busy?'준비 중':s.running?'실행 중':'플레이';$('cancelButton').hidden=!busy||s.authPending;$('memoryInput').disabled=busy||s.running;if(!dirty)$('memoryInput').value=s.settings.memoryMb;document.querySelector('#settingsForm button').disabled=busy||s.running;if(!busy){document.querySelector('.progress-track').hidden=true;status(s.running?'게임 실행 중':'대기 중',s.running||s.configured?'good':'wait');}}
async function run(action){if(!state||state.busy||state.running||starting)return;starting=true;applyState(state);try{const s=await call('run',action);starting=false;applyState(s);}catch(e){starting=false;try{applyState(await call('getState'));}catch{}status('실행 중단','error');showDialog(action==='account'?'로그인할 수 없습니다':'실행할 수 없습니다',e.message,'',undefined,$(action==='account'?'accountButton':'playButton'));}}
document.querySelectorAll('[data-page]').forEach(e=>e.addEventListener('click',()=>navigate(e.dataset.page)));
document.querySelectorAll('[data-window]').forEach(e=>e.addEventListener('click',()=>call('window',e.dataset.window).catch(e=>toast(e.message))));
$('playButton').addEventListener('click',()=>run('play'));
$('accountButton').addEventListener('click',()=>{if(state?.account)showDialog('Microsoft 계정',state.account.name,'로그아웃',async()=>{try{applyState(await call('logout'));}catch(e){toast(e.message);}});else run('account');});
$('cancelButton').addEventListener('click',()=>call('cancel').catch(e=>toast(e.message)));
$('authCancel').addEventListener('click',()=>call('cancel').catch(e=>toast(e.message)));
$('authCopy').addEventListener('click',()=>call('copyLoginCode').catch(e=>toast(e.message)));
$('dialogClose').addEventListener('click',()=>$('infoDialog').close());
$('infoDialog').addEventListener('close',()=>{if(dialogReturnFocus?.isConnected&&!dialogReturnFocus.disabled)dialogReturnFocus.focus();});
$('dialogAction').addEventListener('click',()=>{const fn=dialogAction;$('infoDialog').close();fn?.();});
$('settingsForm').addEventListener('input',()=>{dirty=true;$('saveStatus').textContent='저장하지 않은 변경사항';});
$('settingsForm').addEventListener('submit',async e=>{e.preventDefault();try{const s=await call('saveSettings',{memoryMb:Number($('memoryInput').value)});dirty=false;applyState(s);$('saveStatus').textContent='저장됨';}catch(err){toast(err.message);}});
api.onProgress(p=>{status(p.message,p.stage==='error'?'error':p.stage==='ready'?'good':'wait',p.details||'');const track=document.querySelector('.progress-track');const percent=Number.isFinite(p.percent)?Math.max(0,Math.min(100,p.percent)):null;track.hidden=p.stage==='auth'||p.stage==='ready'||p.stage==='error';track.classList.toggle('busy',percent===null);if(percent===null)track.removeAttribute('aria-valuenow');else track.setAttribute('aria-valuenow',String(percent));$('progressBar').style.transform=`scaleX(${(percent??0)/100})`;});
api.onState(applyState);
api.onAuth(s=>{$('authOverlay').hidden=!s.open;if(s.origin)$('authOrigin').textContent=s.origin;if(s.userCode)$('authCode').textContent=s.userCode;if(!s.open){$('authCode').textContent='';$('authOrigin').textContent='';}});
// 5x7 pixel digits with a one-pixel drop shadow, drawn as SVG so no extra font is needed.
const GLYPHS=['01110100011001110101110011000101110','00100011000010000100001000010001110','01110100010000100110010001000011111','01110100010000100110000011000101110','00011001010100110001111110000100001','11111100001111000001000011000101110','00110010001000011110100011000101110','11111100010000100010001000010000100','01110100011000101110100011000101110','01110100011000101111000010001001100'];
const glyphPaths=GLYPHS.map(bits=>[...bits].map((bit,i)=>bit==='1'?`M${i%5} ${Math.floor(i/5)}h1v1h-1z`:'').join(''));
function pixelDigits(text){return[...text].map(d=>`<svg viewBox="0 0 6 8"><path class="s" transform="translate(1 1)" d="${glyphPaths[d]}"/><path d="${glyphPaths[d]}"/></svg>`).join('');}
const YEAR_END=Date.parse('2027-01-01T00:00:00+09:00');
// Leading units that have run out (00) are hidden; seconds always stay.
function countdown(){const sec=Math.max(0,Math.floor((YEAR_END-Date.now())/1000));let leading=true;for(const[k,v]of Object.entries({days:Math.floor(sec/86400),hours:Math.floor(sec%86400/3600),minutes:Math.floor(sec%3600/60),seconds:sec%60})){const text=String(v).padStart(2,'0'),unit=$('cd-'+k).parentElement,gone=leading&&v===0&&k!=='seconds';if(!gone)leading=false;unit.hidden=gone;if(unit.nextElementSibling)unit.nextElementSibling.hidden=gone;if($('cd-'+k).textContent!==text||!$('px-'+k).firstChild){$('cd-'+k).textContent=text;$('px-'+k).innerHTML=pixelDigits(text);}}}
function splash(){const kst=new Date(Date.now()+9*3600000),month=kst.getUTCMonth()+1,day=kst.getUTCDate();let lines=['새해 복 많이 받으세요!','올해도 수고했어요!','눈 내리는 블록 마을!','카운트다운 준비 완료!'];if(Date.now()>=YEAR_END)lines=['2027년이 밝았습니다!'];else if(month===12&&day>=20&&day<=25)lines.push('메리 크리스마스!');$('splash').textContent=lines[Math.floor(Math.random()*lines.length)];}
splash();
countdown();setInterval(countdown,1000);
call('getState').then(applyState).catch(e=>toast(e.message));
