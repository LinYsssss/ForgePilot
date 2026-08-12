# r6 Design Tokens 定稿(ui-ux-promax 等价流程产物)

> 注:PRD 指定的 `ui-ux-promax` skill 在本执行环境不存在(仅有 trellis-* 系列)。按其精神以等价流程执行:
> 设计方向定稿 → 全量 token 表(含 AA 对比度核算)→ 唯一 tokens.css → 全站消费。本文即设计决策记录。

## 方向:「Precision Workbench」亮色专业工作台

- **告别** Observatory 暗色玻璃拟态/aurora 氛围;**继承**其品牌魂:teal 主色 + 冷蓝灰阶 + 红/琥珀/绿风险语义。
  品牌延续让演示观感上仍是"同一个 RepoSage",且 teal 与 Element 默认蓝 #409EFF 差异最大化——不撞"默认模板脸"。
- 暗色模式本期不做;tokens 全部挂 `:root`,结构上预留(未来加 `html.dark` 块覆写同名 token 即可)。
- 字体:系统栈(移除 Google Fonts 外链,演示服务器网络不可依赖;收尾阶段清 index.html)。
  - body:`"Inter", "Segoe UI", system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`
  - mono:`ui-monospace, "SF Mono", "Cascadia Code", "JetBrains Mono", Consolas, monospace`

## 色板(全部经 AA 核算;文字色在其配对底色上 ≥4.5:1)

### 品牌主色(teal)

| token | 值 | 用途 |
| --- | --- | --- |
| `--rs-primary` | `#0F766E` | 主按钮/激活态/链接(白底 5.4:1) |
| `--rs-primary-dark` | `#0C5E58` | hover/active 深化 |
| light-3/5/7/8/9 | `#579F99` `#87BAB6` `#B7D6D3` `#CFE3E2` `#E7F1F0` | Element 派生阶(向白 30/50/70/80/90% 混合) |

### 冷灰阶(slate)

| token | 值 | 用途 |
| --- | --- | --- |
| `--rs-text` | `#1E293B` | 主文本(14.6:1) |
| `--rs-text-soft` | `#475569` | 次级文本(8.0:1) |
| `--rs-text-dim` | `#64748B` | 弱文本/说明(4.76:1) |
| `--rs-placeholder` | `#94A3B8` | 占位符 |
| `--rs-border` | `#DCE3EC` / strong `#C6D0DD` / faint `#EAEFF5` | 三档描边 |
| `--rs-fill` | `#F1F5F9` / lighter `#F8FAFC` | 填充 |
| `--rs-bg` | `#F4F6FA` | 页面底(微冷) |
| `--rs-surface` | `#FFFFFF` | 卡片面 |

### 风险四级(报告 riskLevel:NONE<LOW<MEDIUM<HIGH,全站唯一映射)

每级四值:`text`(tint 底上 AA)/ `bg`(tint)/ `border` / `solid`(实心条/表盘用)。

| 级 | text | bg | border | solid |
| --- | --- | --- | --- | --- |
| HIGH | `#B91C1C` | `#FEECEB` | `#F5C6C2` | `#DC2626` |
| MEDIUM | `#B45309` | `#FDF0DD` | `#F2D9AC` | `#D97706` |
| LOW | `#15803D` | `#E8F6ED` | `#BFE3CC` | `#16A34A` |
| NONE | `#64748B` | `#EEF2F7` | `#D8E0EA` | `#94A3B8` |

### Finding 严重度五级(后端枚举 CRITICAL/HIGH/MEDIUM/LOW/INFO)

HIGH/MEDIUM/LOW 三级**复用**风险色;两端扩展(现行 styles.css 缺 CRITICAL/INFO 类,本次补齐):

| 级 | text | bg | border | solid |
| --- | --- | --- | --- | --- |
| CRITICAL | `#9F1239` | `#FCE7EF` | `#F3C2D4` | `#BE123C` |
| INFO | `#0369A1` | `#E4F3FB` | `#BBDFF2` | `#0284C7` |

(玫红系 CRITICAL 与橙红系 HIGH 可区分;`sev-NONE` 现行模板在用,保留为 NONE 别名。)

### 状态色(st-* pill 家族,沿用现行归组)

SUCCESS/INDEXED/ACTIVE/CONSUMED/PUBLISHED/PASSED/OPEN/MERGED→LOW 绿组;RUNNING/PENDING/WAIVED→MEDIUM 琥珀组(RUNNING 保留脉冲);FAILED/DEAD/ERROR/CHANGES_REQUESTED→HIGH 红组;CANCELED/CLOSED→NONE 灰组。

### Diff 语义色(亮色代码面,替代 Observatory 暗码块)

add `#E8F6ED`/`#116932` · del `#FEECEB`/`#B91C1C` · hunk `#E4F3FB`/`#0369A1` · meta/行号 `#64748B`/`#94A3B8` · 码面 `#F8FAFC`。

## 形状/排版/阴影/间距

- 圆角:`--el-border-radius-base: 8px`、small `6px`、round `999px`(工作台感,软于 Element 默认 4px)。
- 字号阶:base 14px(同 Element);标题走 `--rs-fs-xl 20 / -2xl 25 / -3xl 31`(沿用 1.2 音程,页面大标题不缩水)。
- 阴影三级(冷色低强度):sm `0 1px 2px rgba(15,23,42,.06)`;md `0 4px 16px rgba(15,23,42,.08)`;lg `0 12px 32px rgba(15,23,42,.14)`。
- 间距:沿用 4px 基准 `--sp-1..16`(页面既有引用直接续用)。

## 落地结构(唯一 tokens 文件)

`frontend/src/tokens.css`,三段:

1. **原生 tokens**:`--rs-*` 全表(上述色板/形状/字体/间距/z-index/motion)。
2. **Element 覆盖**:`:root` 上 `--el-color-primary` 及 light-3/5/7/8/9/dark-2 全 ramp;success/warning/danger/info 四语义同法(success=`#16A34A`、warning=`#D97706`、danger=`#DC2626`、info=`#64748B`,各自派生阶按同混合公式);`--el-border-radius-*`、`--el-font-family`、`--el-font-size-base`、`--el-text-color-*`、`--el-border-color-*`、`--el-fill-color-*`、`--el-bg-color*`、`--el-box-shadow*`。
3. **产品语义工具类**(迁移后长期归宿,替代 styles.css 中对应块):`.risk-*`/`.sev-*`(徽标)、`.st-*`(状态 pill,含 RUNNING 脉冲)、diff 行配色、`.mono`。类名与现行模板完全同名——迁移页零改类名直接换底色。

消费规则:页面**禁止**魔法色值(AC 抽查 `grep -r "#[0-9a-fA-F]\{6\}" src/views` 仅 tokens 文件命中);组件微调走 Element CSS 变量接口,禁止 `::v-deep` 硬改内部结构。

## 交互四态(全站统一,删除页内自造)

loading=`v-loading`/`el-skeleton` · empty=`el-empty` · error=`el-alert type=error` · disabled=组件原生 `disabled`。
