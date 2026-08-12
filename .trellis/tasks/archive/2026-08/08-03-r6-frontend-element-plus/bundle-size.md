# r6 构建体积对比档案

## 口径说明

- 数据来源:容器内(node:22-alpine)`npx vite build` 的产物输出,raw 与 gzip 双列(vite 6 自带 gzip 计算),单位 kB。
- **README 历史基线 202.52 kB 的口径澄清**:该数字与本期实测 raw JS(203.43 kB,含新增 tokens.css 的 import 常量差异)对齐,而非 gzip(实测 gzip JS 仅 72.55 kB)。设计文档"迁移后 gzip 增幅预算 300KB 内"按 **gzip 总量增幅**执行,基准取下表「引依赖后」行(gzip 合计 ≈ 84.11 kB)。
- 每页迁移提交后在下表追加一行(页名 + 三产物 raw/gzip)。

## 尺寸表

| 时点 | index.html | index.css | index.js | gzip 合计 | 模块数 |
| --- | --- | --- | --- | --- | --- |
| 阶段1后 · 引依赖前(tokens.css 已挂,无 element-plus) | 0.83 / 0.48 | 53.04 / 11.08 | 203.43 / 72.55 | 84.11 | 72 |
| **引依赖后 · 空壳还原后(体积基准)** | 0.83 / 0.48 | 53.04 / 11.08 | 203.43 / 72.55 | **84.11** | 72 |
| (参考)空壳探针:单页塞 el-button + el-tag | 0.83 / 0.48 | 86.77 / 15.49 | 251.54 / 90.76 | 106.73 | 1656 |

关键结论:

1. 引依赖前后两次构建**逐字节一致**(产物内容哈希同为 `index-CziIvLeT.css` / `index-BtBkfZaY.js`)——零 el-* 使用时,element-plus + 双 unplugin 对产物体积**零开销**,按需导入纪律成立。
2. 首个使用 Element 组件的页面会一次性摊入基础层(theme 变量 + @vueuse 等运行时),探针实测增量 ≈ raw +81.5 kB / gzip +22.6 kB(css+js 合计);后续页面只按新增组件递增。
3. 距 300KB gzip 增幅预算余量充足(探针全量也仅 +22.6 kB gzip)。

## 逐页迁移追加区

| 页面 | index.html | index.css | index.js | gzip 合计 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 登录页(08ac7b0) | 0.83 / 0.48 | 97.74 / 16.95 | 306.13 / 108.99 | 126.42 | 首页摊入 Element 基础层(form/input/button/card 族),对基准 +42.31 gzip |
| 并行检查点:+概览/项目/仓库/日志四页 | 0.83 / 0.48 | 208.90 / 31.67 | 523.35 / 181.64 | 213.79 | 并行模式下构建集中跑,记检查点而非严格逐页;新增 table/select/skeleton/empty/tag/space/row/col/alert/descriptions 等组件层。对基准 +129.7 gzip,预算(+300)内 |
| **终态(全 21 视图 + 全局收尾,styles.css 退役)** | 0.47 / 0.33 | 234.45 / 32.45 | 540.88 / 186.46 | **219.24** | 干净 npm ci 后构建;含 PR/报告/Agent 三复杂页与 el-dialog/el-timeline;styles.css(-995 行)删除被新增组件层抵消;index.html 随收尾删 Google Fonts 外链而变小。**最终增幅 +135.13 gzip,预算 300 内达标** |

> 终态行 trellis-check 校正(2026-08-11):原记录 html 沿用了删 Google Fonts 前的 0.83/0.48,且「gzip 合计 218.04」漏加 html 一列(与前三行口径不一致);现按复验构建(产物 `index-BbEQrwfo.css` / `index-1B1Zl432.js`,vite 输出口径)重记,结论(预算 300 内)不变。
