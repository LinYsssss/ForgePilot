# 步骤 7 实施记录：水墨动效与降级（收尾）

> 结论：步骤 7 的**实现**在步骤 5 已基本落地，本轮补的是**守卫**——把三条此前只写在注释里的
> 合同约束变成回归测试。npm test 61/0，build 6.30s，体积与步骤 6 基线持平。

## 1. 进入本轮时的实际状态

步骤 7 列出的产物在步骤 5 的骨架里已经存在，逐项核对：

| 步骤 7 要求 | 状态 | 位置 |
|---|---|---|
| TaijiAmbientMark / InkParticleField / InkAmbientScene | 已实现 | `shared/motion/` |
| 单一 pointer observer | 已实现且**已有测试** | `pointerField.js`，用例「exactly one pointer observer…」 |
| 远山 / 墨雾 / 笔触 | 已实现 | `InkAmbientScene.vue`（两重远山、两片墨雾、近景笔触） |
| 落印反馈 | 已实现 | `features/workspace/ReviewActionBar.vue` |
| 粒子 / DPR 预算 | 已实现且已有测试 | `inkParticles.js`，用例「particle budgets stay inside the frozen contract」 |
| reduced / coarse / hidden 降级 | 已实现且已有测试 | `motionPolicy.js`，用例「motion policy suppresses ambient motion on any flag」 |

因此本轮没有重写实现，只补缺口。

## 2. 补齐的三条回归测试

1. **`ambient layer ships zero external assets, so texture failure cannot occur`**
   环境层禁止出现 `url(...)`（`data:` 除外）与图片 import。
2. **`at most three continuously animated large-blur layers exist`**
   合同 §7「持续动画的大面积模糊层 ≤3」。判据取真实开销来源：同一元素既命中 `filter: blur(≥10px)`
   规则、又命中 `animation: … infinite` 规则。实测识别出且仅识别出三层云带
   （`ink-cloud cloud-one/two/three`）；用例另有 `heavy > 0` 的自检，防止解析失效后变成空跑绿灯。
3. **`blur stays off the content plane and within 12px on the shell`**
   合同 §18/§81/§126 的模糊分级。

## 3. 偏离记录：纹理失败态在本实现中不存在

合同 §198 写的是「**纹理失败时**使用纯 CSS 纸面与静态渐变」——它预设会加载一张纹理图。

实现走得更远：纸纹 `.ink-grain` 由两层 `repeating-linear-gradient` 直接生成
（`InkAmbientScene.vue`），**环境层零外部资源**。于是：

- 「纹理加载失败」这个失败态**按构造不存在**，不需要兜底分支；
- 合同 §197 的「静态纹理单项 ≤120KB、总环境资源 ≤300KB」以 0 字节满足；
- §198 的「不为装饰阻塞首屏」同样按构造成立——装饰不产生任何额外请求。

**这是比合同更强的满足，不是未实现。** 风险在反向：日后有人图省事塞回一张纹理图，
就会同时引入「失败态」和「首屏阻塞」两个合同已经写明要避免的问题，而兜底代码并不存在。
第 2 节第 1 条测试正是为这个反向风险设的闸。

合同文本本身未改——`ui-design.md` 是冻结件，改它要走 `ui-decisions.md` 流程；
本条按「实现强于合同」记录在此即可，不构成 drift。

## 4. 「无 blur」一项的口径澄清

步骤 7 验证清单里的「无 blur」曾被误读成「浏览器不支持 blur 时的降级」。查合同后确认是**两件事**：

- **§18 / §81 / §126**：语义内容面**恒不使用模糊**，shell 局部 ≤12px，环境层可到 32px。
  这是设计漂移约束——已由第 2 节第 3 条测试钉死。
- **§187 的 `blur`**：指 **window blur 事件**（窗口失焦即停观察器），与 CSS 模糊无关。
  该行为在 `useMotionPolicy.js` 已实现并有测试覆盖。

实测内容面（`features/workspace/` 六组件）确实零模糊；现有模糊全部落在
shell/auth/atelier，且最大 12px，均在合同额度内。

## 5. 未做与去向

- **性能降级的运行时实测**（帧率、长任务、真实设备 DPR）需要浏览器环境，归步骤 9 质量门。
- **390/768/1440 截图与 console/network 证据**同样需要可运行栈，归步骤 1 / 步骤 9。
- 本轮只加测试与文档，未改任何运行时代码，因此不存在视觉回归风险。
