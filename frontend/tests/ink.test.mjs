import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { MOTION_FLAGS, createMotionPolicy } from '../src/shared/motion/motionPolicy.js'
import { createPointerField, normalizePointer } from '../src/shared/motion/pointerField.js'
import {
  CINNABAR_INTERVAL,
  DPR_MAX,
  PARTICLE_MAX,
  PARTICLE_MIN,
  RADIUS_MAX,
  RADIUS_MIN,
  clampDpr,
  createParticles,
  particleColor,
  particleCount,
  stepParticles,
} from '../src/shared/motion/inkParticles.js'
import {
  MOBILE_LAYOUT_QUERY,
  RAIL_DRAWER_QUERY,
  createDrawerController,
  nextTrapIndex,
} from '../src/features/shell/drawerController.js'
import { progressModel } from '../src/shared/ui/progressModel.js'
import { SEAL_TONES, resolveSealTone } from '../src/shared/ui/sealTone.js'

// ---------------------------------------------------------------------------
// 墨境书院骨架(重构步骤 5)测试:
//   1. 令牌 drift gate —— ui-design.md v1.0 §3 冻结值逐一断言;
//   2. 动效偏好状态机 / 指针场 / 墨粒预算 / 抽屉合同的纯逻辑行为;
//   3. 路由整合与认证语义的源码锚点(旧壳层不回退、观察器唯一)。
// ---------------------------------------------------------------------------

/* ---------- 1. Design tokens drift gate ---------- */

// 合同 §3.1 + §3.2 冻结色值;任何改动必须先过 drift gate(ui-decisions.md)
const FROZEN_TOKENS = {
  '--canvas': '#F1EDE5',
  '--surface-paper': '#FBF8F0',
  '--surface-raised': '#FFFDFA',
  '--surface-muted': '#F3EEE5',
  '--ink-strong': '#1F211D',
  '--ink-default': '#3B3E37',
  '--ink-muted': '#62665C',
  '--line-soft': '#DED7CB',
  '--line-strong': '#C7BEAF',
  '--cinnabar': '#9E382B',
  '--cinnabar-hover': '#842D24',
  '--cinnabar-soft': '#F1DCD5',
  '--mineral-cyan': '#176F70',
  '--mineral-cyan-soft': '#D8E8E4',
  '--amber-ink': '#86621E',
  '--success-ink': '#276A53',
  '--code-surface': '#F5F0E6',
  '--code-text': '#252720',
  '--code-gutter': '#E6DED0',
  '--code-added-bg': '#DDEBDD',
  '--code-added-text': '#245F45',
  '--code-removed-bg': '#F2D9D3',
  '--code-removed-text': '#8B302A',
  '--code-focus': '#176F70',
}

test('ink tokens carry the frozen v1.0 contract values verbatim', async () => {
  const css = await readFile(new URL('../src/shared/theme/ink-tokens.css', import.meta.url), 'utf8')
  for (const [token, value] of Object.entries(FROZEN_TOKENS)) {
    assert.ok(css.includes(`${token}: ${value};`), `${token} 必须等于冻结值 ${value}`)
  }
  // §3.4 间距 4/8/12/16/24/32/48 与圆角 2/4/6
  for (const step of [4, 8, 12, 16, 24, 32, 48]) {
    assert.ok(css.includes(`--ink-sp-${step}: ${step}px;`), `间距令牌 --ink-sp-${step}`)
  }
  assert.ok(css.includes('--ink-radius-paper: 2px;'))
  assert.ok(css.includes('--ink-radius-control: 4px;'))
  assert.ok(css.includes('--ink-radius-layer: 6px;'))
  // 阴影只表达层级,上限为合同值
  assert.ok(css.includes('--ink-shadow-layer: 0 12px 36px rgb(46 39 28 / 0.14);'))
  // 步骤 6 追加的对话框遮罩洗色:已批准原型 .dialog-backdrop 逐字值
  // (步骤 5 check 记录 §4-8 预留),非新颜色
  assert.ok(css.includes('--ink-wash-dialog: rgb(31 33 29 / 0.34);'))
})

test('ink components take colors from tokens only (no hardcoded hex)', async () => {
  const files = [
    '../src/features/shell/InkShell.vue',
    '../src/features/shell/CaseIndex.vue',
    '../src/features/shell/InkDialog.vue',
    '../src/features/auth/LoginGate.vue',
    '../src/features/workspace/PaperWorkspace.vue',
    '../src/features/workspace/FindingLedger.vue',
    '../src/features/workspace/EvidenceDiff.vue',
    '../src/features/workspace/ReviewActionBar.vue',
    '../src/features/workspace/AnnotationRail.vue',
    '../src/features/workspace/RunCaseList.vue',
    '../src/pages/InkAtelierPage.vue',
    '../src/shared/ui/SealBadge.vue',
    '../src/shared/ui/BrushProgress.vue',
    '../src/shared/motion/InkAmbientScene.vue',
    '../src/shared/motion/TaijiAmbientMark.vue',
    '../src/shared/motion/InkParticleField.vue',
    '../src/shared/theme/ink-base.css',
  ]
  for (const file of files) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    const hex = source.match(/#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b/g)
    assert.equal(hex, null, `${file} 含硬编码色值 ${hex},颜色只能经 ink-tokens.css 进入`)
  }
})

/* ---------- 2. Motion preference state machine ---------- */

test('motion policy suppresses ambient motion on any flag and labels the mode', () => {
  const policy = createMotionPolicy()
  assert.equal(policy.ambientAllowed(), true)
  assert.equal(policy.mode(), 'normal')

  for (const flag of MOTION_FLAGS) {
    policy.set(flag, true)
    assert.equal(policy.ambientAllowed(), false, `${flag} 生效时环境动效必须停止`)
    policy.set(flag, false)
  }
  assert.equal(policy.ambientAllowed(), true)

  policy.set('reduced', true)
  assert.equal(policy.mode(), 'reduced')
  policy.set('reduced', false)
  policy.set('coarse', true)
  assert.equal(policy.mode(), 'reduced') // 触屏与减动效同样落静态构图
  policy.set('coarse', false)

  policy.set('hidden', true)
  assert.equal(policy.mode(), 'paused')
  policy.set('hidden', false)

  assert.equal(policy.toggleStatic(), true)
  assert.equal(policy.mode(), 'static')
  policy.set('reduced', true)
  assert.equal(policy.mode(), 'static') // 用户显式静态优先于系统偏好标签
  policy.set('reduced', false)
  assert.equal(policy.toggleStatic(), false)
  assert.equal(policy.mode(), 'normal')
})

test('motion policy notifies only on real changes and ignores unknown flags', () => {
  const policy = createMotionPolicy()
  let emits = 0
  policy.subscribe(() => { emits += 1 })
  policy.set('hidden', true)
  policy.set('hidden', true) // 无变化不通知
  assert.equal(emits, 1)
  policy.set('bogus', true)
  assert.equal(emits, 1)
  assert.equal(policy.ambientAllowed(), false)
  policy.set('hidden', false)
  assert.equal(emits, 2)
})

/* ---------- 3. Pointer field ---------- */

test('pointer normalization maps viewport coords to [-1, 1] and survives zero sizes', () => {
  assert.deepEqual(normalizePointer(960, 250, 1920, 1000), { x: 0, y: -0.5 })
  assert.deepEqual(normalizePointer(1920, 1000, 1920, 1000), { x: 1, y: 1 })
  assert.deepEqual(normalizePointer(100, 100, 0, 0), { x: 0, y: 0 })
})

test('pointer field coalesces moves into one frame, clamps, and resets to zero', () => {
  const writes = []
  const frames = []
  const field = createPointerField({
    write: (x, y) => writes.push([x, y]),
    schedule: (cb) => { frames.push(cb); return frames.length },
    cancel: (id) => { frames[id - 1] = null },
  })

  field.move(0.4, -0.2)
  field.move(0.8, 0.6) // 同帧第二次移动:不追加调度
  assert.equal(frames.filter(Boolean).length, 1)
  frames[0]()
  assert.deepEqual(writes, [[0.8, 0.6]]) // 只写最后一次坐标

  field.move(5, -5) // 越界钳制
  frames.at(-1)()
  assert.deepEqual(writes.at(-1), [1, -1])

  field.move(0.3, 0.3) // 挂起帧被 reset 取消,且立即归零
  field.reset()
  assert.deepEqual(writes.at(-1), [0, 0])
  assert.equal(frames.at(-1), null)
  assert.deepEqual(field.current(), { x: 0, y: 0 })
})

/* ---------- 4. Ink particles budget ---------- */

test('particle budgets stay inside the frozen contract', () => {
  // 数量:30–64;DPR ≤ 1.5;半径 0.7–3.2
  assert.equal(PARTICLE_MIN, 30)
  assert.equal(PARTICLE_MAX, 64)
  assert.equal(particleCount(320), 30)
  assert.equal(particleCount(1440), Math.round(1440 / 26))
  assert.equal(particleCount(9999), 64)
  assert.equal(particleCount(NaN), 30)

  assert.equal(DPR_MAX, 1.5)
  assert.equal(clampDpr(2), 1.5)
  assert.equal(clampDpr(1.25), 1.25)
  assert.equal(clampDpr(undefined), 1)

  const near1 = () => 0.999999
  const particles = createParticles({ count: 64, width: 1440, height: 900, random: near1 })
  for (const p of particles) {
    assert.ok(p.r >= RADIUS_MIN && p.r <= RADIUS_MAX, `半径 ${p.r} 超出 0.7–3.2 预算`)
  }
  // 朱砂占比 ≤ 1/13,首粒不取朱砂
  const warm = particles.filter((p) => p.warm)
  assert.ok(warm.length <= Math.ceil(64 / 13), `朱砂粒 ${warm.length} 超出 1/13 预算`)
  assert.equal(particles[0].warm, false)
  assert.equal(particles[CINNABAR_INTERVAL].warm, true)
  assert.match(particleColor(particles[CINNABAR_INTERVAL]), /158 56 43/)
  assert.match(particleColor(particles[0]), /47 68 60/)
})

test('particles drift slowly and wrap at the edges', () => {
  const particles = [
    { x: 5, y: -10, r: 1, vx: 0, vy: 0, alpha: 0.05, warm: false },
    { x: -10, y: 50, r: 1, vx: 0, vy: 0, alpha: 0.05, warm: false },
  ]
  stepParticles(particles, { width: 100, height: 80 })
  assert.equal(particles[0].y, 88) // 顶部漂出 → 底部回绕
  assert.equal(particles[1].x, 108) // 左侧漂出 → 右侧回绕
})

/* ---------- 4b. Ambient asset & blur budgets (ui-design.md §7 §197–198) ---------- */

// 合同 §198「不为装饰阻塞首屏;纹理失败时使用纯 CSS 纸面与静态渐变」。
// 实现比合同更进一步:纸纹由 repeating-linear-gradient 生成,环境层零外部资源,
// 于是「纹理加载失败」这一失败态根本不存在。本用例钉死这个更强的性质——
// 一旦有人塞回 url(...) 或 import 图片,降级分支就重新出现而合同的兜底代码并不存在。
test('ambient layer ships zero external assets, so texture failure cannot occur', async () => {
  const files = [
    '../src/shared/motion/InkAmbientScene.vue',
    '../src/shared/motion/TaijiAmbientMark.vue',
    '../src/shared/motion/InkParticleField.vue',
    '../src/shared/theme/ink-base.css',
    '../src/shared/theme/ink-tokens.css',
  ]
  for (const file of files) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    const urls = source.match(/url\(\s*['"]?(?!data:)[^)]+\)/g)
    assert.equal(urls, null, `${file} 引入了外部资源 ${urls};环境层必须纯 CSS 生成`)
    const assets = source.match(/from\s+['"][^'"]+\.(png|jpe?g|webp|gif|avif|svg)['"]/g)
    assert.equal(assets, null, `${file} 导入了图片资源 ${assets};装饰不得产生额外请求`)
  }
})

// 合同 §7「持续动画的大面积模糊层 ≤3」——原先只写在 InkAmbientScene 的注释里,没有守卫。
// 判据取真实开销来源:同一元素既落在大面积 blur 规则上、又落在 infinite 动画规则上。
// 静态模糊(远山/墨雾/笔触)不计,它们不产生逐帧重绘。
test('at most three continuously animated large-blur layers exist', async () => {
  const source = await readFile(
    new URL('../src/shared/motion/InkAmbientScene.vue', import.meta.url), 'utf8')
  const style = source.slice(source.indexOf('<style'), source.lastIndexOf('</style>'))

  // 收集 { 叶子类名 -> 规则体 };选择器形如 .a / .a .b / .a, .b
  const blurred = new Set()
  const animated = new Set()
  for (const [, selector, body] of style.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    // 跳过 @规则、关键帧百分比,以及嵌套花括号切剩下的声明碎片(含 ; 或 : 的不是选择器)
    if (/[@%;:]/.test(selector)) continue
    const leaves = [...selector.matchAll(/\.([\w-]+)(?![\w-])/g)]
      .map((m) => m[1])
      .filter((_, i, all) => i === all.length - 1 || selector.includes(','))
    const blurPx = body.match(/filter:\s*blur\((\d+(?:\.\d+)?)px\)/)
    if (blurPx && Number(blurPx[1]) >= 10) leaves.forEach((c) => blurred.add(c))
    if (/animation:[^;]*infinite/.test(body)) leaves.forEach((c) => animated.add(c))
  }

  // 模板里逐元素求交:一个元素的 class 同时命中两个集合才算数
  const template = source.slice(0, source.indexOf('</template>'))
  let heavy = 0
  for (const [, classAttr] of template.matchAll(/class="([^"]+)"/g)) {
    const classes = classAttr.split(/\s+/)
    if (classes.some((c) => blurred.has(c)) && classes.some((c) => animated.has(c))) heavy += 1
  }
  assert.ok(heavy > 0, '未识别到任何持续动画模糊层,判据已失效——先修用例再改实现')
  assert.ok(heavy <= 3, `持续动画的大面积模糊层有 ${heavy} 个,超出合同 §7 的 ≤3 预算`)
})

// 合同把模糊按平面分级(§18 语义层禁 blur / §81 内容面恒为 0、shell 局部 ≤12px / §126 主内容纸面自身不模糊)。
// 这条分级此前只靠人自觉。两半都钉:内容面一处模糊都不许有,shell 允许但不得越过 12px。
test('blur stays off the content plane and within 12px on the shell', async () => {
  const contentPlane = [
    '../src/features/workspace/PaperWorkspace.vue',
    '../src/features/workspace/FindingLedger.vue',
    '../src/features/workspace/EvidenceDiff.vue',
    '../src/features/workspace/ReviewActionBar.vue',
    '../src/features/workspace/AnnotationRail.vue',
    '../src/features/workspace/RunCaseList.vue',
  ]
  for (const file of contentPlane) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    const blur = source.match(/(?:backdrop-)?filter:\s*blur\([^)]*\)/g)
    assert.equal(blur, null, `${file} 在内容面用了模糊 ${blur};合同 §81 要求内容面恒为 0`)
  }

  const shell = [
    '../src/features/shell/InkShell.vue',
    '../src/features/shell/CaseIndex.vue',
    '../src/features/shell/InkDialog.vue',
    '../src/features/auth/LoginGate.vue',
  ]
  for (const file of shell) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    for (const [, px] of source.matchAll(/(?:backdrop-)?filter:\s*blur\((\d+(?:\.\d+)?)px\)/g)) {
      assert.ok(Number(px) <= 12, `${file} 的模糊 ${px}px 超出合同 §81 的 shell ≤12px`)
    }
  }
})

/* ---------- 5. Drawer contract (frozen in ui-design.md §11) ---------- */

test('drawer breakpoints match the frozen contract queries', () => {
  assert.equal(MOBILE_LAYOUT_QUERY, '(max-width: 767px)')
  assert.equal(RAIL_DRAWER_QUERY, '(max-width: 1279px)')
})

test('closed drawers leave the a11y tree only in drawer layouts', () => {
  const controller = createDrawerController()

  controller.setLayout({ mobile: false, railDrawer: false }) // 桌面三栏
  assert.deepEqual(
    [controller.snapshot().navHidden, controller.snapshot().railHidden],
    [false, false]
  )

  controller.setLayout({ mobile: false, railDrawer: true }) // 平板
  assert.equal(controller.snapshot().navHidden, false)
  assert.equal(controller.snapshot().railHidden, true)

  controller.setLayout({ mobile: true, railDrawer: true }) // 手机双抽屉
  assert.equal(controller.snapshot().navHidden, true)
  assert.equal(controller.snapshot().railHidden, true)
})

test('drawers are exclusive, move focus on open, and return it on close', () => {
  const controller = createDrawerController({ mobile: true, railDrawer: true })

  assert.equal(controller.openDrawer('nav'), 'nav-close') // 打开 → 焦点进关闭按钮
  assert.equal(controller.snapshot().open, 'nav')
  assert.equal(controller.snapshot().navHidden, false)

  assert.equal(controller.openDrawer('rail'), 'rail-close') // 互斥:换边自动关另一侧
  assert.equal(controller.snapshot().open, 'rail')
  assert.equal(controller.snapshot().navHidden, true)

  assert.equal(controller.escapeTarget(), 'rail') // Escape 优先关朱批
  assert.equal(controller.closeDrawer(), 'rail-toggle') // 关闭 → 焦点归还触发器
  assert.equal(controller.snapshot().open, null)
  assert.equal(controller.escapeTarget(), null)
  assert.equal(controller.openDrawer('bogus'), null)
})

test('leaving a drawer breakpoint closes the open drawer', () => {
  const controller = createDrawerController({ mobile: false, railDrawer: true })
  controller.openDrawer('rail')
  assert.equal(controller.setLayout({ mobile: false, railDrawer: false }), true)
  assert.equal(controller.snapshot().open, null)
  assert.equal(controller.snapshot().railHidden, false) // 桌面回归常规列

  controller.setLayout({ mobile: true, railDrawer: true })
  controller.openDrawer('nav')
  assert.equal(controller.setLayout({ mobile: false, railDrawer: true }), true)
  assert.equal(controller.snapshot().open, null)
})

test('tab trap wraps focus at both ends and pulls stray focus back in', () => {
  assert.equal(nextTrapIndex(5, 4, false), 0) // 尾 → 首
  assert.equal(nextTrapIndex(5, 0, true), 4) // 首 → 尾(Shift)
  assert.equal(nextTrapIndex(5, 2, false), null) // 中间交给浏览器
  assert.equal(nextTrapIndex(5, -1, false), 0) // 焦点游离 → 拉回
  assert.equal(nextTrapIndex(0, -1, false), null)
})

/* ---------- 6. Shared UI pure logic ---------- */

test('progress model derives statuses, done count, and stroke fraction', () => {
  const steps = ['接卷', '索引', '静态分析', '安全分析', '复核', '报告'].map((label, i) => ({ key: `s${i}`, label }))
  const running = progressModel(steps, 3)
  assert.deepEqual(running.steps.map((s) => s.status), ['done', 'done', 'done', 'current', 'pending', 'pending'])
  assert.equal(running.doneCount, 3)
  assert.equal(running.fraction, 3 / 5)

  const finished = progressModel(steps, 6)
  assert.ok(finished.steps.every((s) => s.status === 'done'))
  assert.equal(finished.fraction, 1)

  assert.deepEqual(progressModel([], 2), { steps: [], doneCount: 0, total: 0, fraction: 0 })
  assert.equal(progressModel(steps, -3).doneCount, 0)
})

test('seal tones stay dual-encoded and cinnabar stays exclusive to critical', () => {
  assert.equal(resolveSealTone('critical').className, 'tone-critical')
  assert.equal(resolveSealTone('critical').shape, 'square') // 形状参与编码
  assert.equal(resolveSealTone('unknown').className, 'tone-neutral')
  for (const tone of Object.values(SEAL_TONES)) {
    assert.ok(tone.glyph, '每个语气都必须带印记字(非颜色编码)')
  }
})

/* ---------- 7. Route integration and auth-semantics anchors ---------- */

test('ink entry is isolated by route meta while every legacy route survives', async () => {
  const routerSource = await readFile(new URL('../src/router.js', import.meta.url), 'utf8')
  assert.match(routerSource, /name: 'inkAtelier'/)
  assert.match(routerSource, /meta: \{ shell: 'ink' \}/)
  for (const name of ['dashboard', 'projects', 'repository', 'pullRequests', 'knowledge', 'reviews', 'agent', 'aiLogs']) {
    assert.match(routerSource, new RegExp(`name: '${name}'`), `旧路由 ${name} 必须保留`)
  }
  assert.match(routerSource, /agent-evidence=/) // 证据外链重定向不动

  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  assert.match(app, /isInkShell/)
  assert.match(app, /meta\.shell === 'ink'/)
  assert.match(app, /LoginView v-else-if/) // 旧登录/壳层分流原样保留
  assert.match(app, /AppShell v-else/)
})

test('exactly one pointer observer writes the ink pointer vars; only ambient consumes them', async () => {
  const observer = await readFile(new URL('../src/shared/motion/usePointerField.js', import.meta.url), 'utf8')
  assert.equal((observer.match(/addEventListener\('pointermove'/g) || []).length, 1)
  assert.match(observer, /--ink-pointer-x/)
  assert.match(observer, /--ink-pointer-y/)

  const ambient = await readFile(new URL('../src/shared/motion/InkAmbientScene.vue', import.meta.url), 'utf8')
  assert.match(ambient, /--ink-pointer-x/)
  // 合同 §7 实现规则:环境层持续动效只改 transform/opacity,禁止 margin 等布局属性
  for (const file of ['../src/shared/motion/InkAmbientScene.vue', '../src/shared/motion/TaijiAmbientMark.vue']) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /margin(-[a-z]+)?\s*:/, `${file} 的环境动效不得动布局属性(margin)`)
  }
  // 信息层不得消费指针变量(装饰与信息隔离)
  for (const file of [
    '../src/features/shell/InkShell.vue',
    '../src/features/shell/CaseIndex.vue',
    '../src/features/shell/InkDialog.vue',
    '../src/features/workspace/PaperWorkspace.vue',
    '../src/features/workspace/FindingLedger.vue',
    '../src/features/workspace/EvidenceDiff.vue',
    '../src/features/workspace/ReviewActionBar.vue',
    '../src/features/workspace/AnnotationRail.vue',
    '../src/features/workspace/RunCaseList.vue',
    '../src/pages/InkAtelierPage.vue',
    '../src/features/auth/LoginGate.vue',
  ]) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /--ink-pointer/, `${file} 不得读写指针变量`)
  }
})

test('login gate reuses the existing auth semantics without a second source', async () => {
  const gate = await readFile(new URL('../src/features/auth/LoginGate.vue', import.meta.url), 'utf8')
  assert.match(gate, /api\('\/auth\/login'/) // 同一后端合同
  assert.match(gate, /await initCsrf\(\)/) // 登录后立刻重引导 CSRF(轮换令牌)
  assert.match(gate, /useSession/) // 同一会话单例,不建第二套认证源
  assert.doesNotMatch(gate, /localStorage|sessionStorage/)
  assert.doesNotMatch(gate, /ysainlin/)
})
