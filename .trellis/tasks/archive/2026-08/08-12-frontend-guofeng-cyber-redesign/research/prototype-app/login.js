(() => {
  const app = document.querySelector('.ink-app')
  const root = document.documentElement
  const canvas = document.querySelector('#inkParticleCanvas')
  const ctx = canvas.getContext('2d', { alpha: true })
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const coarse = window.matchMedia('(pointer: coarse)')
  let pointerX = 0
  let pointerY = 0
  let pointerFrame = 0
  let particleFrame = 0
  let particles = []
  let width = 0
  let height = 0
  let toastTimer = 0
  let submitting = false

  function allowed() { return !app.classList.contains('static-mode') && !reduced.matches && !coarse.matches && !document.hidden && document.hasFocus() }
  function resetPointer() { root.style.setProperty('--pointer-x', 0); root.style.setProperty('--pointer-y', 0) }
  function updatePointer() { pointerFrame = 0; if (!allowed()) return resetPointer(); root.style.setProperty('--pointer-x', pointerX.toFixed(3)); root.style.setProperty('--pointer-y', pointerY.toFixed(3)) }
  function onPointer(event) { if (!allowed()) return; pointerX = (event.clientX / innerWidth - .5) * 2; pointerY = (event.clientY / innerHeight - .5) * 2; if (!pointerFrame) pointerFrame = requestAnimationFrame(updatePointer) }

  function resize() {
    const ratio = Math.min(devicePixelRatio || 1, 1.5)
    width = innerWidth; height = innerHeight
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio)
    canvas.style.width = `${width}px`; canvas.style.height = `${height}px`
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
    const count = Math.max(30, Math.min(64, Math.round(width / 25)))
    particles = Array.from({ length: count }, (_, i) => ({ x: Math.random()*width, y: Math.random()*height, r: .8+Math.random()*2.4, vx: -.03+Math.random()*.06, vy: -.04-Math.random()*.07, a: .04+Math.random()*.1, warm: i%15===0 }))
    draw(false)
  }
  function draw(advance=true) {
    ctx.clearRect(0,0,width,height); ctx.globalCompositeOperation='multiply'
    particles.forEach(p => { if(advance){p.x += p.vx+pointerX*.015; p.y += p.vy+pointerY*.01; if(p.y < -8){p.y=height+8;p.x=Math.random()*width} if(p.x < -8)p.x=width+8;if(p.x>width+8)p.x=-8} ctx.beginPath();ctx.fillStyle=p.warm?`rgb(158 56 43 / ${p.a*.65})`:`rgb(47 68 60 / ${p.a})`;ctx.arc(p.x,p.y,p.r,0,Math.PI*2);ctx.fill() })
  }
  function animate(){ particleFrame=0; if(!allowed()) return draw(false); draw(true); particleFrame=requestAnimationFrame(animate) }
  function sync(){ if(particleFrame)cancelAnimationFrame(particleFrame);particleFrame=0;if(allowed())particleFrame=requestAnimationFrame(animate);else draw(false) }
  function toast(message){ const el=document.querySelector('#toast');clearTimeout(toastTimer);el.textContent=message;el.hidden=false;toastTimer=setTimeout(()=>el.hidden=true,2200) }
  function enter(){ const submit=document.querySelector('.login-submit');if(submitting)return;submitting=true;submit.disabled=true;submit.setAttribute('aria-busy','true');submit.textContent='正在验明身份…';setTimeout(()=>location.href='./index.html', allowed()?520:80) }

  addEventListener('pointermove', onPointer, {passive:true})
  addEventListener('resize', resize, {passive:true})
  addEventListener('blur', () => {resetPointer();sync()})
  document.addEventListener('visibilitychange', () => {resetPointer();sync()})
  document.querySelector('#motionToggle').addEventListener('click', e => { const active=app.classList.toggle('static-mode');e.currentTarget.setAttribute('aria-pressed',String(active));e.currentTarget.textContent=active?'启用太极水墨':'静态墨境';if(active)resetPointer();sync();toast(active?'已切换静态墨境':'已启用太极水墨与墨粒') })
  document.querySelector('#demoLogin').addEventListener('click', () => {document.querySelector('#email').value='reviewer@reposage.local';document.querySelector('#password').value='prototype';document.querySelector('#loginError').hidden=true;toast('演示账户已填入')})
  document.querySelector('#loginForm').addEventListener('submit', e => {e.preventDefault();if(submitting)return;const email=e.currentTarget.email;const password=e.currentTarget.password;const missing=!email.value.trim()?email:!password.value?password:null;if(missing){document.querySelector('#loginError').hidden=false;email.setAttribute('aria-invalid',String(!email.value.trim()));password.setAttribute('aria-invalid',String(!password.value));missing.focus();return}document.querySelector('#loginError').hidden=true;email.removeAttribute('aria-invalid');password.removeAttribute('aria-invalid');enter()})
  document.querySelector('#forgotLink').addEventListener('click', e => {e.preventDefault();toast('原型：将跳转至组织账号恢复流程')})
  resize();sync()
})()
