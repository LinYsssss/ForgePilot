# Design：墨境书院前端重构技术方案

> 视觉方向 A 已批准。用户允许较大幅度调整现有前端架构；本方案因此保留业务/API 边界，重建前端表现层和模块边界。完整 UI 规则见 `research/ui-design.md`，目前为待原型验证的 v0.2 草案。

## 1. Architecture decision

保留：Vue 3、Vite、Vue Router、后端 API、认证与权限语义、核心业务流程和路由可达性。

允许重构：`src` 目录、页面布局、组件层级、主题令牌、状态组织、Element Plus 使用方式、动效基础设施和测试分层。

不做：在本任务内修改后端合同、权限规则、评测/提示词逻辑；一次性删除所有旧页面后再补功能；让装饰系统读取或持有业务数据。

## 2. Target module shape

```text
frontend/src/
├─ app/
│  ├─ bootstrap/        # 应用初始化、providers、错误边界
│  ├─ router/           # 路由定义、守卫、页面元数据
│  └─ shell/            # AppShell、CaseIndex、top context
├─ pages/               # 路由编排；不放可复用业务逻辑
├─ widgets/
│  ├─ review-workspace/ # 主审查纸面
│  ├─ annotation-rail/  # 朱批栏
│  └─ agent-progress/   # 笔触式阶段进度
├─ features/
│  ├─ auth/
│  ├─ projects/
│  ├─ repository/
│  ├─ pull-requests/
│  ├─ reviews/
│  ├─ agent/
│  ├─ knowledge/
│  └─ ai-logs/
├─ entities/            # Project/Review/Finding/AgentRun 等领域模型与适配器
└─ shared/
   ├─ api/              # 现有 HTTP 合同的兼容层
   ├─ ui/               # PaperSurface、SealBadge、Drawer、ActionBar 等
   ├─ theme/            # primitive/semantic/component tokens
   ├─ motion/           # pointer observer、motion preference、InkAmbientScene
   └─ lib/              # 无业务含义的工具
```

规则：依赖只能由 app/pages/widgets/features 指向 entities/shared；shared 不依赖业务 feature；页面不直接发 HTTP；ambient motion 不依赖 entities。

## 3. Language and state migration

- 新模块优先使用 TypeScript 与 `<script setup>`；旧 JavaScript 可在迁移期共存。
- API response 在 `entities/*/adapter` 边界转换为前端模型，页面不传播后端偶然字段形状。
- 首个切片继续使用局部 feature store/composable；只有出现跨路由共享、缓存和调试的真实压力时才提案引入 Pinia。
- 不创建第二套认证源；新 shell 复用现有认证与权限判定，随后再将其封装进 feature 边界。

## 4. Component foundation

- Element Plus 不再是可见设计语言的权威。
- 可暂时保留其 Dialog/Popover/Table/Form 等成熟行为，但必须通过 `shared/ui` 包装；页面禁止直接覆盖 Element Plus 内部 DOM 选择器。
- `PaperSurface`、`SealBadge`、`BrushProgress`、`FindingLedger`、`EvidenceDiff`、`AnnotationRail` 和 `ReviewActionBar` 由项目自己定义稳定 API。
- 原型通过后盘点：保留、包装或替换每个 Element Plus 类别；没有迁移收益的组件不为“纯重写”而重写。

## 5. Rendering planes

```text
AppShell
├─ InkAmbientScene     # 纯装饰，可禁用、无 pointer events、无业务状态
│  ├─ TaijiAmbientMark # 登录主视觉/工作台低透明
│  ├─ InkParticleField # 原生 Canvas，低密度、可停止
│  ├─ PaperGrain       # 静态纹理/CSS fallback
│  ├─ FarMountain      # max pointer shift 3px
│  ├─ InkMist          # max 6px, blur ≤ 16px
│  └─ BrushAccent      # max 10px, 可隐藏
├─ CaseIndex           # 稳定导航层
└─ PaperWorkspace      # 稳定业务层
   ├─ ContextHeader
   ├─ RiskSummary
   ├─ BrushProgress
   ├─ FindingLedger
   ├─ EvidenceDiff
   ├─ AnnotationRail
   └─ ReviewActionBar
```

`InkAmbientScene` 必须可从 DOM 完全移除而不改变布局。所有业务内容拥有不透明或近实色纸面，任何 ambient 元素均 `aria-hidden`。

## 6. Motion service

- `shared/motion/usePointerField.ts` 在 shell 生命周期内注册唯一 pointer listener。
- `shared/motion/useInkParticles.ts` 管理原生 Canvas 墨粒：30–64 个、DPR ≤ 1.5，不读取业务状态。
- listener 只把归一化坐标写入根节点 CSS variables，使用 RAF 合并更新，不写 Vue reactive state。
- `useMotionPolicy.ts` 统一处理 reduced-motion、coarse pointer、document visibility、window focus 和手动“静态模式”。
- 一次性 `InkReveal`、`BrushProgress`、`SealConfirm` 使用 CSS/SVG；必须支持中断、重复触发和立即完成。
- 允许低密度原生 Canvas 墨粒；禁止 Canvas 流体、高密度粒子、WebGL 和 scroll-jacking。颗粒在 static/reduced/coarse/hidden/unfocused 时停止 RAF。

## 7. Responsive architecture

- Shell 使用 CSS Grid areas，不在组件内通过 JavaScript 判断设备宽度。
- AnnotationRail 在桌面是列、平板/手机是同一个语义组件的 Drawer 呈现，不复制业务逻辑。
- FindingLedger 提供 table/list 两种渲染器，复用 selection/filter/action 状态。
- 移动端按任务顺序重排，而非缩放桌面三栏。

## 8. Vertical-slice migration

1. 建立新 tokens、shared UI、motion policy、太极/墨粒环境层、登录门禁与新 AppShell，但先挂在隔离入口/feature flag 下。
2. 迁移 Agent 工作台 + Reviews 证据路径，打通真实 API、主动作、success/error/retry。
3. 与旧路径做行为对照；通过原型、浏览器和测试门后，将新 shell 设为默认。
4. 依次迁移 Dashboard → Projects → Repository → PullRequests → Knowledge → AI Logs。
5. 每迁移一页，删除对应旧表现层与无引用样式；禁止长期保留双份业务逻辑。

## 9. Test strategy

- Unit：token/motion policy、API adapter、feature store 与状态转换。
- Component：keyboard/focus、reduced motion、coarse pointer、Drawer 焦点、Diff 模式、错误恢复。
- Route integration：认证守卫、深链、刷新、返回、权限与 404。
- Browser：390/768/1440，normal/reduced/static，console/network、重复提交和失败恢复。
- Visual：选定原型与实现截图对比；专门检查纸纹/墨迹是否侵入文字和代码。

## 10. Budgets and rollback

- 环境效果运行时代码目标 ≤ 12KB gzip；环境静态资源总计 ≤ 300KB；Canvas DPR ≤ 1.5，粒子 ≤ 64。
- 目标交互帧率 ≥ 55fps；明显掉帧时优先关闭近景、再关闭雾层，业务层不降级。
- 新 shell 与页面按提交独立回滚；API/认证兼容层使旧页面在迁移期仍可运行。
- R7 未提交改动先安全落盘；实现必须在独立 `codex/frontend-guofeng-cyber-redesign` 分支/工作树进行。
- 正式构建和验收使用 Node 22。

## 11. Gates

- Gate A：A「墨境书院」已选定，**通过**。
- Gate B：1440/768/390 交互原型及动效/静态模式获批，待执行。
- Gate C：根据原型修订并冻结 `research/ui-design.md`，待执行。
- Gate D：Agent + Reviews 纵向切片行为、无障碍、响应式和性能通过后，才能扩面。
