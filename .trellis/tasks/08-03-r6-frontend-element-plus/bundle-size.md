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
| (迁移开始后追加) | | | | | |
