<template>
  <div class="ink-ambient" :class="variant === 'login' ? 'is-login' : 'is-workspace'" aria-hidden="true">
    <InkParticleField :variant="variant" />
    <div class="ink-parallax ph-taiji">
      <TaijiAmbientMark class="scene-taiji" variant="watermark" />
    </div>
    <div class="ink-grain"></div>
    <div class="ink-parallax ph-cloud-one"><div class="ink-cloud cloud-one"></div></div>
    <div class="ink-parallax ph-cloud-two"><div class="ink-cloud cloud-two"></div></div>
    <div class="ink-parallax ph-cloud-three"><div class="ink-cloud cloud-three"></div></div>
    <div class="ink-parallax ph-mountain-far">
      <svg class="ink-mountain" viewBox="0 0 1440 900" preserveAspectRatio="none">
        <path d="M0 710 C150 650 225 690 325 590 C430 480 510 650 620 565 C760 455 840 650 960 555 C1085 455 1190 600 1440 470 L1440 900 L0 900Z" />
      </svg>
    </div>
    <div class="ink-parallax ph-mountain-near">
      <svg class="ink-mountain" viewBox="0 0 1440 900" preserveAspectRatio="none">
        <path d="M0 790 C170 650 280 790 410 675 C535 565 640 770 790 655 C940 545 1105 705 1440 560 L1440 900 L0 900Z" />
      </svg>
    </div>
    <div class="ink-parallax ph-mist-one"><div class="ink-mist mist-one"></div></div>
    <div class="ink-parallax ph-mist-two"><div class="ink-mist mist-two"></div></div>
    <div v-if="variant === 'workspace'" class="ink-parallax ph-brush"><div class="ink-brush-accent"></div></div>
  </div>
</template>

<script setup>
import InkParticleField from './InkParticleField.vue'
import TaijiAmbientMark from './TaijiAmbientMark.vue'

// 环境装饰层(合同 §2 ambient plane / §5 / §7):纸纹 + 远山 + 三层云带 +
// 淡墨雾 + 太极水印 + 墨粒。规则:
//   - aria-hidden、pointer-events:none;从 DOM 移除不改变任何布局;
//   - 不持有业务数据、不含交互控件;
//   - 指针视差只发生在 .ink-parallax 包装层,消费根节点 --ink-pointer-x/y,
//     位移预算:远山 2–3px / 云 2–3px / 墨雾 6px / 近景笔触 10px;
//   - 持续动画的大面积模糊层仅三层云带(38–54s 漂移);墨雾为静态洗色,
//     仅随指针低幅移动;纸纹/远山属低成本层(合同 §7 实现规则);
//   - 降级(static/reduced/coarse)由 ink-base.css 统一冻结 transform 与动画。
defineProps({
  variant: { type: String, default: 'workspace' }, // workspace | login
})
</script>

<style scoped>
.ink-ambient {
  position: fixed;
  inset: 0;
  z-index: -1;
  overflow: hidden;
  pointer-events: none;
  background:
    radial-gradient(circle at 55% 4%, rgb(255 255 255) 0 16%, transparent 52%),
    radial-gradient(circle at 12% 42%, rgb(255 255 255 / 0.72), transparent 38%),
    linear-gradient(145deg, var(--ink-ambient-grad-a), var(--ink-ambient-grad-b));
}

/* ---- 指针视差包装层:只改 transform,合成层内完成 ---- */
.ink-parallax { position: absolute; will-change: transform; }

/* ---- 太极水印 ---- */
.ph-taiji {
  top: 11%;
  right: 4%;
  width: min(34vw, 430px);
  aspect-ratio: 1;
  opacity: 0.072;
  filter: blur(0.3px);
  transform: translate3d(calc(var(--ink-pointer-x) * -3px), calc(var(--ink-pointer-y) * -2px), 0);
}
/* 登录变体:原型以 translate(-50%,-50%) 居中于 (31%, 50%);此处等价换算为
   静态 top/left(1440×900 下同一构图),避免居中 transform 与视差包装层
   在静态降级时互相覆盖 */
.is-login .ph-taiji {
  top: 12%;
  right: auto;
  left: 7%;
  width: min(52vw, 680px);
  opacity: 0.105;
  transform: translate3d(calc(var(--ink-pointer-x) * -3px), calc(var(--ink-pointer-y) * -2px), 0);
}
.ink-ambient .scene-taiji { position: absolute; inset: 0; }

/* ---- 纸纹(低成本层;基准 0.11,呼吸 ±0.03,36s ∈ 合同 24–40s) ---- */
.ink-grain {
  position: absolute;
  inset: 0;
  mix-blend-mode: multiply;
  background-image:
    repeating-linear-gradient(97deg, transparent 0 8px, var(--ink-ambient-grain-warm) 9px 10px),
    repeating-linear-gradient(8deg, transparent 0 17px, rgb(255 255 255 / 0.26) 18px 19px);
  animation: ink-grain-breathe 36s ease-in-out infinite;
  opacity: 0.11;
}
@keyframes ink-grain-breathe {
  0%, 100% { opacity: 0.08; }
  50% { opacity: 0.13; }
}

/* ---- 三层云带(持续动画的大面积模糊层,合同预算 ≤3 即此三层) ---- */
.ph-cloud-one { top: 7%; left: -24%; transform: translate3d(calc(var(--ink-pointer-x) * 2px), calc(var(--ink-pointer-y) * 1px), 0); }
.ph-cloud-two { top: 35%; right: -28%; transform: translate3d(calc(var(--ink-pointer-x) * -3px), calc(var(--ink-pointer-y) * -2px), 0); }
.ph-cloud-three { bottom: 0; left: 10%; transform: translate3d(calc(var(--ink-pointer-x) * 2px), calc(var(--ink-pointer-y) * 2px), 0); }
.ink-cloud {
  width: 76vw;
  min-width: 720px;
  height: 230px;
  opacity: 0.62;
  filter: blur(24px);
  background:
    radial-gradient(ellipse at 16% 58%, rgb(255 255 255 / 0.92) 0 11%, transparent 31%),
    radial-gradient(ellipse at 42% 46%, rgb(255 255 255 / 0.84) 0 17%, transparent 39%),
    radial-gradient(ellipse at 72% 60%, rgb(255 255 255 / 0.78) 0 14%, transparent 36%);
}
.cloud-one { animation: ink-cloud-drift-one 38s ease-in-out infinite alternate; }
.cloud-two { opacity: 0.48; animation: ink-cloud-drift-two 46s ease-in-out infinite alternate; }
.cloud-three { opacity: 0.38; animation: ink-cloud-drift-one 54s ease-in-out infinite alternate-reverse; }
/* 漂移只动 transform/opacity(合同 §7;原型的 margin 漂移为布局属性,
   此处等价换算:左锚定层平移同号,右锚定层 margin-right 增大 = 向左移,
   故取反号,幅度与 38/46/54s 节奏不变) */
@keyframes ink-cloud-drift-one {
  from { transform: translateX(-1.5vw); opacity: 0.46; }
  to { transform: translateX(2vw); opacity: 0.66; }
}
@keyframes ink-cloud-drift-two {
  from { transform: translateX(2vw); opacity: 0.34; }
  to { transform: translateX(-1.5vw); opacity: 0.52; }
}

/* ---- 远山两重(静态低成本层;位移 2 / -3px ∈ 合同 2–3px) ---- */
.ph-mountain-far,
.ph-mountain-near {
  inset: auto 0 0;
  height: 86%;
}
.ph-mountain-far { transform: translate3d(calc(var(--ink-pointer-x) * 2px), calc(var(--ink-pointer-y) * 1.5px), 0); }
.ph-mountain-near { transform: translate3d(calc(var(--ink-pointer-x) * -3px), calc(var(--ink-pointer-y) * -2px), 0); }
.ink-mountain { width: 100%; height: 100%; }
.ink-mountain path { fill: var(--ink-ambient-mountain); }
.ph-mountain-far .ink-mountain { opacity: 0.045; filter: blur(5px); }
.ph-mountain-near .ink-mountain { opacity: 0.028; filter: blur(9px); }

/* ---- 淡墨雾(静态洗色,仅指针 ±6px;不参与持续动画) ---- */
.ph-mist-one,
.ph-mist-two {
  width: 50vw;
  height: 34vw;
  min-width: 420px;
  min-height: 300px;
}
.ph-mist-one { left: 18%; bottom: -9%; transform: translate3d(calc(var(--ink-pointer-x) * 6px), calc(var(--ink-pointer-y) * 4px), 0); }
.ph-mist-two { right: -12%; top: 11%; transform: translate3d(calc(var(--ink-pointer-x) * -6px), calc(var(--ink-pointer-y) * -4px), 0); }
.ink-mist {
  width: 100%;
  height: 100%;
  border-radius: 46% 54% 61% 39% / 55% 40% 60% 45%;
  background: radial-gradient(ellipse, rgb(255 255 255 / 0.82), var(--ink-ambient-mist-tint) 45%, transparent 73%);
  filter: blur(28px);
}
.mist-one { transform: rotate(-9deg); }
.mist-two { opacity: 0.55; transform: rotate(20deg); }

/* ---- 近景笔触(位移 10px 上限;仅工作台) ---- */
.ph-brush {
  right: 9%;
  bottom: 7%;
  width: 240px;
  height: 80px;
  transform: translate3d(calc(var(--ink-pointer-x) * 10px), calc(var(--ink-pointer-y) * 7px), 0);
}
.ink-brush-accent {
  width: 100%;
  height: 100%;
  opacity: 0.045;
  filter: blur(0.5px);
  background:
    linear-gradient(6deg, transparent 38%, var(--ink-ambient-brush) 40% 46%, transparent 48%),
    radial-gradient(ellipse at 78% 56%, var(--ink-ambient-brush) 0 4%, transparent 6%);
  transform: rotate(-5deg);
}

/* ---- 移动端:静态低对比构图(合同 §4 mobile) ---- */
@media (max-width: 560px) {
  .ph-mountain-far,
  .ph-mountain-near { height: 50%; }
  .ph-mist-two,
  .ph-brush { display: none; }
}
</style>
