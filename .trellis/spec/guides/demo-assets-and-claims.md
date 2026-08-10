# Demo Assets & Honest Claims(演示资产与写实口径纪律)

> 三条约束合并成文,约束所有任务(清理、重构、文档、演示准备)。此前散落在各任务 PRD 里,此处为常驻单源。

---

## 1. demo-repos 故意缺陷保留义务

`demo-repos/` 三个演示仓库(mall-order-service / payment-settlement-service / tenant-user-center)中的 **43 条缺陷是刻意植入的验证素材**,与 `docs/演示素材与缺陷对照表.md` 逐条对应(A 类通用 4 / B 类规则依赖 17 / C 类事故重犯 19 / 跨类 3)。

- **任何"清理""修复""现代化"任务不得触碰 demo-repos 内部代码**——修掉缺陷就毁掉了产品能力的证明素材。
- PR 分支由 `demo-repos/patches/<repo>/feature-*.patch` 唯一承载,**不许手工编辑 patch**:内容直接决定提交 SHA,重建脚本 `--verify` 按 `scripts/demo-repos-expected-sha.txt` 六条比对,这是防篡改设计(README「六、patches 目录」)。确需改动走完整流程:改 patch → 重建 → 更新期望 SHA → 同步四处写死 SHA 的文档(README 末节列表)。
- `knowledge-noise/` 干扰文档同样是素材(证伪"检索排序合理"用),不是垃圾,不许清理。

## 2. README 诚实声明不可回退

`demo-repos/README.md`「五、诚实边界」七条(缺陷系植入、AI 不保证全中、会有误报、noise 是刻意干扰、规范为演示编写、仓库可编译不可运行、沙箱镜像未建即降级)**只许增补,不许削弱或删除**。顶层 `README.md` 同理:能力条目带实测日期与范围(如沙箱链路 e2e 附 2026-08-09 实跑记录),未实施的能力明确标注「未实施」而非隐藏(A5 质量门详情页先例)。

## 3. 能力表述以实测为准

- 每一条对外能力/指标表述,必须能指到一份**已存在的**实测产物:`evaluation/results/` 跑分、任务目录里的 e2e 记录、CI 运行。没有产物就不写。
- 数字口径:漏报率/误报率独立呈报;禁用「零漏报」及等价说法,唯一批准口径见 `.trellis/spec/backend/prompt-management.md` 规则五。
- 演示叙述主动交代边界(答辩时主动说明 noise 干扰项与植入性质)——写实是这个项目的产品立场,不是免责声明。
