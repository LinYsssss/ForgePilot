(() => {
  const app = document.querySelector('.ink-app')
  const root = document.documentElement
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const coarse = window.matchMedia('(pointer: coarse)')
  let raf = 0
  let pointerX = 0
  let pointerY = 0
  let toastTimer = 0
  let lastFocus = null
  const particleCanvas = document.querySelector('#inkParticleCanvas')
  const particleContext = particleCanvas?.getContext('2d', { alpha: true })
  let particleFrame = 0
  let particles = []
  let particleWidth = 0
  let particleHeight = 0

  function resizeParticles() {
    if (!particleCanvas || !particleContext) return
    const ratio = Math.min(window.devicePixelRatio || 1, 1.5)
    particleWidth = window.innerWidth
    particleHeight = window.innerHeight
    particleCanvas.width = Math.round(particleWidth * ratio)
    particleCanvas.height = Math.round(particleHeight * ratio)
    particleCanvas.style.width = `${particleWidth}px`
    particleCanvas.style.height = `${particleHeight}px`
    particleContext.setTransform(ratio, 0, 0, ratio, 0, 0)
    const count = Math.max(26, Math.min(58, Math.round(particleWidth / 28)))
    particles = Array.from({ length: count }, (_, index) => ({
      x: Math.random() * particleWidth,
      y: Math.random() * particleHeight,
      r: .7 + Math.random() * 2.2,
      vx: -.035 + Math.random() * .07,
      vy: -.055 - Math.random() * .08,
      alpha: .035 + Math.random() * .095,
      warm: index % 13 === 0
    }))
    drawParticles(false)
  }

  function drawParticles(advance = true) {
    if (!particleContext) return
    particleContext.clearRect(0, 0, particleWidth, particleHeight)
    particleContext.globalCompositeOperation = 'multiply'
    particles.forEach((particle) => {
      if (advance) {
        particle.x += particle.vx + pointerX * .018
        particle.y += particle.vy + pointerY * .012
        if (particle.y < -8) { particle.y = particleHeight + 8; particle.x = Math.random() * particleWidth }
        if (particle.x < -8) particle.x = particleWidth + 8
        if (particle.x > particleWidth + 8) particle.x = -8
      }
      particleContext.beginPath()
      particleContext.fillStyle = particle.warm ? `rgb(158 56 43 / ${particle.alpha * .72})` : `rgb(47 68 60 / ${particle.alpha})`
      particleContext.arc(particle.x, particle.y, particle.r, 0, Math.PI * 2)
      particleContext.fill()
    })
  }

  function animateParticles() {
    particleFrame = 0
    if (!motionAllowed()) return drawParticles(false)
    drawParticles(true)
    particleFrame = requestAnimationFrame(animateParticles)
  }

  function syncParticles() {
    if (particleFrame) cancelAnimationFrame(particleFrame)
    particleFrame = 0
    if (motionAllowed()) particleFrame = requestAnimationFrame(animateParticles)
    else drawParticles(false)
  }

  const $ = (selector) => document.querySelector(selector)
  const $$ = (selector) => [...document.querySelectorAll(selector)]

  function motionAllowed() {
    return !app.classList.contains('static-mode') && !reduced.matches && !coarse.matches && !document.hidden && document.hasFocus()
  }

  function updatePointer() {
    raf = 0
    if (!motionAllowed()) return resetPointer()
    root.style.setProperty('--pointer-x', pointerX.toFixed(3))
    root.style.setProperty('--pointer-y', pointerY.toFixed(3))
  }

  function onPointerMove(event) {
    if (!motionAllowed()) return
    pointerX = (event.clientX / window.innerWidth - .5) * 2
    pointerY = (event.clientY / window.innerHeight - .5) * 2
    if (!raf) raf = requestAnimationFrame(updatePointer)
  }

  function resetPointer() {
    root.style.setProperty('--pointer-x', 0)
    root.style.setProperty('--pointer-y', 0)
  }

  function showToast(message) {
    const toast = $('#toast')
    clearTimeout(toastTimer)
    toast.textContent = message
    toast.hidden = false
    toastTimer = setTimeout(() => { toast.hidden = true }, 2300)
  }

  function setExpanded(button, expanded) {
    button?.setAttribute('aria-expanded', String(expanded))
  }

  function openNav() {
    $('#caseIndex').classList.add('open')
    setExpanded($('#navToggle'), true)
    $('#navClose').focus()
  }

  function closeNav(returnFocus = true) {
    $('#caseIndex').classList.remove('open')
    setExpanded($('#navToggle'), false)
    if (returnFocus) $('#navToggle').focus()
  }

  function openRail() {
    $('#annotationRail').classList.add('open')
    setExpanded($('#railToggle'), true)
    $('#railClose').focus()
  }

  function closeRail(returnFocus = true) {
    $('#annotationRail').classList.remove('open')
    setExpanded($('#railToggle'), false)
    if (returnFocus) $('#railToggle').focus()
  }

  function selectFinding(row, scroll = false) {
    $$('.finding-row').forEach((item) => {
      const selected = item === row
      item.classList.toggle('selected', selected)
      item.setAttribute('aria-selected', String(selected))
    })
    $('#evidenceId').textContent = row.dataset.id.toUpperCase()
    $('#evidenceTitleText').textContent = row.dataset.title
    $('#evidenceFile').textContent = `${row.dataset.file}:${row.dataset.line}`
    $('#evidenceConfidence').textContent = row.dataset.confidence
    $('#evidenceDescription').textContent = row.dataset.desc
    if (scroll) {
      $('.evidence-section').scrollIntoView({ behavior: motionAllowed() ? 'smooth' : 'auto', block: 'start' })
      $('.diff').focus({ preventScroll: true })
    }
  }

  function setState(next) {
    app.dataset.state = next
    const error = next === 'error'
    const offline = next === 'offline'
    $('#errorBanner').hidden = !error
    $('#offlineBanner').hidden = !offline
    $('#connection').classList.toggle('offline', offline)
    $('#connection').innerHTML = `<span></span>${offline ? '离线阅读' : '实时同步'}`
    $('#approveButton').disabled = error || offline
    if (error) showToast('已切换到验证失败场景')
    if (offline) showToast('已切换到离线恢复场景')
  }

  function openDialog() {
    lastFocus = document.activeElement
    const dialog = $('#confirmDialog')
    dialog.hidden = false
    document.body.style.overflow = 'hidden'
    $('#dialogCancel').focus()
  }

  function closeDialog() {
    $('#confirmDialog').hidden = true
    document.body.style.overflow = ''
    lastFocus?.focus()
  }

  function confirmApproval() {
    closeDialog()
    const layer = $('#resultLayer')
    layer.hidden = false
    setTimeout(() => {
      layer.hidden = true
      $('#approveButton').textContent = '已批准'
      $('#approveButton').disabled = true
      showToast('原型：审批记录已写入案卷')
    }, motionAllowed() ? 1200 : 450)
  }

  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('blur', resetPointer)
  document.addEventListener('visibilitychange', () => { resetPointer(); syncParticles() })
  reduced.addEventListener?.('change', resetPointer)
  coarse.addEventListener?.('change', resetPointer)

  $('#motionToggle').addEventListener('click', (event) => {
    const active = app.classList.toggle('static-mode')
    event.currentTarget.setAttribute('aria-pressed', String(active))
    event.currentTarget.textContent = active ? '启用水墨' : '静态墨境'
    if (active) resetPointer()
    syncParticles()
    showToast(active ? '已切换静态墨境' : '已启用太极水墨与墨粒动效')
  })

  $('#navToggle').addEventListener('click', openNav)
  $('#navClose').addEventListener('click', () => closeNav())
  $('#railToggle').addEventListener('click', openRail)
  $('#railClose').addEventListener('click', () => closeRail())

  $$('.finding-row').forEach((row) => row.addEventListener('click', () => selectFinding(row)))
  $$('[data-focus]').forEach((button) => button.addEventListener('click', () => {
    const row = document.querySelector(`[data-id="${button.dataset.focus}"]`)
    if (row) selectFinding(row, true)
    closeRail(false)
  }))

  $$('.filter-chips button').forEach((button) => button.addEventListener('click', () => {
    $$('.filter-chips button').forEach((item) => item.classList.toggle('active', item === button))
    showToast(`已应用筛选：${button.textContent}`)
  }))

  $$('.view-switch button').forEach((button) => button.addEventListener('click', () => {
    $$('.view-switch button').forEach((item) => item.classList.toggle('active', item === button))
    showToast(`已切换为${button.textContent} Diff（原型示意）`)
  }))

  $('#simulateError').addEventListener('click', () => setState('error'))
  $('#simulateOffline').addEventListener('click', () => setState('offline'))
  $('#retryAction').addEventListener('click', () => {
    setState('normal')
    showToast('失败步骤已恢复，安全分析继续')
    $('#errorBanner').previousElementSibling?.scrollIntoView({ block: 'nearest' })
  })
  $('#restoreConnection').addEventListener('click', () => {
    setState('normal')
    showToast('连接已恢复，待提交操作已同步')
  })
  $('#refreshRun').addEventListener('click', () => showToast('案卷已刷新 · 0 项新变更'))
  $('#rejectButton').addEventListener('click', () => {
    $('#reviewComment').value = $('#reviewComment').value || '需要补充校验后重新提交。'
    showToast('原型：已标记为退回修改')
  })
  $('#approveButton').addEventListener('click', openDialog)
  $('#dialogCancel').addEventListener('click', closeDialog)
  $('#dialogConfirm').addEventListener('click', confirmApproval)
  $('#confirmDialog').addEventListener('click', (event) => { if (event.target === event.currentTarget) closeDialog() })

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return
    if (!$('#confirmDialog').hidden) return closeDialog()
    if ($('#annotationRail').classList.contains('open')) return closeRail()
    if ($('#caseIndex').classList.contains('open')) return closeNav()
  })

  const dialog = $('.confirm-dialog')
  dialog.addEventListener('keydown', (event) => {
    if (event.key !== 'Tab') return
    const focusable = [...dialog.querySelectorAll('button:not(:disabled)')]
    const first = focusable[0]
    const last = focusable.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  })

  window.addEventListener('resize', resizeParticles, { passive: true })
  resizeParticles()
  syncParticles()
  selectFinding($('.finding-row.selected'))
})()
