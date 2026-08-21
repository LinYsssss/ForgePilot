const directions = {
  ledger: {
    label: "A · Evidence Ledger / 证据案卷",
    summary: "Editorial reading flow: context rail → evidence paper → human decision rail.",
    fit: "适合需要长阅读、证据追溯和可信决策记录的评审场景；密度中等。",
    density: "正文使用温和衬线标题与中性无衬线辅助文字，留白承担层级。",
    memory: "Finding 像一条可追溯批注，把 AC、知识来源和代码位置钉在同一张证据纸上。",
    forbidden: "古风卷轴、印章、墨迹覆盖代码、装饰性动效，或把 Legacy 主题当成已选方案。",
    template: () => `
      <article class="fixture ledger">
        <aside class="ledger-rail"><h3>Context rail</h3><h2>Checkout guard</h2><p>Project / Billing API</p><span class="tag">AC-02</span><span class="tag">Revision 18</span><hr><p>Requirement text and selected knowledge references remain quiet and readable.</p></aside>
        <section class="ledger-paper"><p class="eyebrow">Evidence ledger · finding 01</p><h2>Currency is accepted without a boundary check.</h2><p>The diff allows an unsupported currency code to reach the fee calculation path.</p><div class="annotation"><strong>Why this exists</strong><p>AC-02 requires an explicit supported-currency decision before money arithmetic.</p></div><button class="locator" data-locator>src/billing/FeePolicy.java · L84–L91 · AC-02</button><div class="diff" data-diff><span class="del">− return fee(amount, currency);</span>\n<span class="add">+ return fee(amount, currency); // no allow-list check</span>\n<span>  return rounding.apply(fee);</span></div></section>
        <aside class="decision-rail"><h3>Human decision</h3><span class="tag risk">AT RISK</span><h2>Review context</h2><p>AI confidence, Finding lifecycle, and human decision stay separate.</p><span class="tag safe">Evidence linked</span></aside>
      </article>`,
  },
  console: {
    label: "B · Precision Review Console / 精密审查台",
    summary: "Operational console: finding index → stable evidence split → decision strip.",
    fit: "适合日常工程吞吐和快速定位；三方向中密度最高，操作路径最短。",
    density: "系统无衬线 + 等宽代码，固定栏位保持 file、line、AC、source 同屏。",
    memory: "Evidence locator 始终可见，切换 Finding 时不丢失文件、行号和 AC 语境。",
    forbidden: "通用后台仪表盘、KPI 卡片优先、霓虹 HUD，或把置信度/状态/决定合成一个风险分数。",
    template: () => `
      <article class="fixture console">
        <header class="console-bar"><div><p class="eyebrow">REVIEW / REVISION 18</p><h2>Billing API · precision queue</h2></div><div><span class="tag">2 findings</span><span class="tag risk">1 at risk</span></div></header>
        <aside class="finding-list"><h3>Finding index</h3><button class="finding-row selected">F-01 · currency boundary<br><small>FeePolicy.java:84 · AC-02</small></button><button class="finding-row">F-02 · duplicate contract<br><small>InvoiceMapper.ts:31 · AC-04</small></button></aside>
        <section class="console-evidence"><h3>Selected evidence</h3><p>Currency reaches arithmetic without the project allow-list.</p><button class="locator" data-locator>LOCATE · FeePolicy.java:84–91 · AC-02 · requirement</button><div class="diff" data-diff><span class="del">− return fee(amount, currency);</span>\n<span class="add">+ return fee(amount, currency); // unchecked</span>\n<span>  return rounding.apply(fee);</span></div><p><span class="tag risk">Finding: AT RISK</span><span class="tag">AI confidence: 0.86</span><span class="tag">Decision: pending</span></p></section>
        <footer class="decision-strip"><strong>Decision zone</strong><span>Human decision is intentionally inert in this visual fixture.</span></footer>
      </article>`,
  },
  causal: {
    label: "C · Causal Trace Workspace / 因果链工作台",
    summary: "Three source panes feed a central conclusion: requirement + knowledge + diff → finding.",
    fit: "最能表达 ForgePilot 的因果链差异化，适合解释为什么出现一个 Finding；密度中高。",
    density: "可读无衬线与等宽证据并置；来源标识不冒充业务状态颜色。",
    memory: "“为什么存在这个 Finding”在页面上直接可见，不借助 Agent 图或隐藏编排。",
    forbidden: "节点图、画布平移、动画流水线、把内部 Agent/编排概念暴露给用户。",
    template: () => `
      <article class="fixture causal">
        <header class="causal-heading"><p class="eyebrow">CAUSAL TRACE · FINDING 01</p><h2>Unsupported currency can enter fee arithmetic.</h2><p>Three sources remain inspectable while the conclusion stays human-readable.</p></header>
        <section class="source-pane"><span class="source-key">01 · Requirement / AC</span><h3>Boundary before money math</h3><p>AC-02 requires a supported-currency decision before arithmetic.</p><span class="tag">AC-02</span></section>
        <section class="conclusion-pane"><span class="source-key">Conclusion</span><div class="causal-thread"><h3>Finding rationale</h3><p>Requirement and code evidence agree: the boundary is absent.</p><span class="tag risk">AT RISK</span></div><button class="locator" data-locator>Evidence locator · FeePolicy.java:84–91</button><div class="diff" data-diff><span class="del">− return fee(amount, currency);</span>\n<span class="add">+ return fee(amount, currency); // unchecked</span>\n<span>  return rounding.apply(fee);</span></div></section>
        <section class="source-pane"><span class="source-key">02 · Project knowledge</span><h3>Currency policy</h3><p>Billing policy lists CNY, USD, and EUR as the supported set.</p><span class="tag safe">source linked</span></section>
        <section class="source-pane"><span class="source-key">03 · Diff</span><h3>Code evidence</h3><p>FeePolicy.java · lines 84–91</p><div class="diff" data-diff><span class="del">− return fee(amount, currency);</span>\n<span class="add">+ return fee(amount, currency); // unchecked</span></div></section>
      </article>`,
  },
};

const preview = document.querySelector("#preview-frame");
const note = document.querySelector("#direction-note");
const fitNote = document.querySelector("#fit-note");
const densityNote = document.querySelector("#density-note");
const memoryNote = document.querySelector("#memory-note");
const forbiddenNote = document.querySelector("#forbidden-note");
let activeDirection = "ledger";
let longDiff = false;

function render() {
  const direction = directions[activeDirection];
  preview.dataset.direction = activeDirection;
  preview.innerHTML = direction.template();
  note.textContent = `${direction.label} — ${direction.summary}`;
  fitNote.textContent = direction.fit;
  densityNote.textContent = direction.density;
  memoryNote.textContent = direction.memory;
  forbiddenNote.textContent = direction.forbidden;
  preview.classList.toggle("long-diff", longDiff);
  applyLongDiff();
  document.querySelectorAll(".direction-button[data-direction]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.direction === activeDirection);
  });
}

function applyLongDiff() {
  preview.querySelectorAll("[data-diff]").forEach((diff) => {
    if (longDiff) {
      const repeated = Array.from({ length: 18 }, (_, index) =>
        `<span class="${index % 3 === 0 ? "add" : ""}">${index % 3 === 0 ? "+" : " "}  supporting evidence line ${String(index + 1).padStart(2, "0")} · same fixture</span>`,
      ).join("\n");
      diff.insertAdjacentHTML("beforeend", `\n${repeated}`);
    }
  });
}

document.querySelectorAll(".direction-button").forEach((button) => {
  button.addEventListener("click", () => {
    activeDirection = button.dataset.direction;
    render();
  });
});
document.querySelectorAll(".viewport-button").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".viewport-button").forEach((item) => item.classList.remove("is-active"));
    button.classList.add("is-active");
    preview.className = `preview-frame viewport-${button.dataset.viewport}${longDiff ? " long-diff" : ""}`;
  });
});
document.querySelector("#reduced-motion").addEventListener("change", (event) => {
  document.body.classList.toggle("reduced-motion", event.target.checked);
});
document.querySelector("#focus-locator").addEventListener("click", () => {
  preview.querySelector("[data-locator]")?.focus();
});
document.querySelector("#toggle-diff").addEventListener("click", (event) => {
  longDiff = !longDiff;
  event.target.setAttribute("aria-pressed", String(longDiff));
  event.target.textContent = longDiff ? "Hide long diff" : "Show long diff";
  preview.classList.toggle("long-diff", longDiff);
  if (longDiff) {
    applyLongDiff();
  } else {
    render();
  }
});
render();
