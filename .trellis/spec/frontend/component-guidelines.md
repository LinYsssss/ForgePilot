# Component Guidelines

> `frontend/src/components/`(共享展示组件)与 `views/`(路由页面)的组件写法,提炼自现有 21 个 SFC(零 `<style>` 块是现状,也是规则)。

---

## SFC 形态

- `<template>` 在前、`<script setup>` 在后,纯 JS(不写 TS、不写 Options API)。
- Props 用对象式 `defineProps` 声明类型与默认值;数组默认值必须是工厂:`defineProps({ findings: { type: Array, default: () => [] } })`(`components/agent/AgentFindings.vue`)。
- 事件用 `defineEmits` 显式列出(`AppShell.vue` 的 `['navigate', 'refresh', 'logout']`);可 v-model 的选择器用 `update:modelValue`(`KnowledgeDocPicker.vue`)。

## 职责边界

- **components/ 是展示组件**:数据从 props 进、动作从 emits 出,不 import composable 单例、不发请求。单例只在 `views/` 与 `App.vue` 落地(见 [directory-structure.md](./directory-structure.md))。
- 展示态的小函数(格式化、拼链接)留在组件 `<script setup>` 内;**会被测试或复用的决策逻辑抽成同目录纯 JS 文件**——先例:补丁审批可用性判定在 `components/agent/patchApprovalPolicy.js`(`canApprovePatch`),被 `tests/smoke.test.mjs` 直接 import 单测,而组件只调用它。node test runner 不渲染组件,留在 `.vue` 里的逻辑测不到。

## 样式:Observatory 设计系统单源

- **全部样式在 `src/styles.css`**(设计令牌 + 主题 + 组件类),组件不写 `<style>` 块、不写内联样式。新视觉先扩展 styles.css 的 token/类,再在模板引用。
- 复用既有类:`panel` / `panel-head` / `badge` / `muted` / `empty` / `warning`;严重度着色用 `'sev-' + finding.severity` 动态类——类名后缀就是后端下发的 severity 字符串(Agent 侧 `FindingSeverity` 枚举、legacy 审查侧 HIGH/MEDIUM/LOW),styles.css 当前定义 `sev-HIGH/MEDIUM/LOW/NONE`;后端新增/改枚举值必须同步补 `sev-*` 类,否则该级别静默失去着色(跨层锚点)。
- 动效必须尊重 `prefers-reduced-motion`(styles.css 已有对应媒体查询,新动效加入同一分支)。

## 交互细节(既有先例,保持一致)

- 展开/收起用原生 `<details>/<summary>`(证据抽屉),Esc 关闭走 `@keydown.esc` + `removeAttribute('open')`(`AgentFindings.vue`)。
- 列表键盘导航用 `directives/listNav.js`,不逐组件手写 keydown。
- 深链定位用 hash 路由 query(`#/agent?evidence=path:line`);旧格式外链在 `router.js` 做重定向兼容,组件只生成新格式。
