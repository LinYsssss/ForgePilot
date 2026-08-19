# miss- 批次创作说明（漏报专项 6 例）

> 形态：干净大 diff 埋单一深藏缺陷，每例恰 1 条预期 finding。head 总量均在 8k-18k
> 字符、diff 触及 3-8 文件、缺陷完整落于单文件且不依赖跨切片上下文（自证据都在
> 缺陷文件内或该例 knowledge/ 文档中）。所有素材为独立副本改编，包名统一虚构
> com.acme.*，事故编号 EVAL- 前缀，无真实密钥/姓名/路径。
> 行号均已用 grep -n / sed -n 对 head 实际文件复核，证据摘录见各节。

## 1. miss-payhub-refund-cast（development，JAVA，14240 字符 / 8 文件 head）

- **素材出处**：demo-repos `patches/payment-settlement-service/feature-instant-settlement.patch`
  （302 行）无害化改编——P1-P15 十五条缺陷全部按 settlement-rules 修正成"干净大改动"
  底稿（费率走配置、BigDecimal 精确转分、验签+幂等回调、角色校验+审计），再单点回植
  **P14 金额强转截断**于 OpenApiPaymentController.refund。
- **缺陷一句话**：退款入口 `double amountYuan` 乘 100 后 `(long)` 强转截断（0.29 元→28 分），
  违反 knowledge/amount-rules.md 第 1 节"禁止浮点、必须 BigDecimal 精确转换"红线；
  同文件 submitInstant/adminRefund 均用 `movePointRight(2).longValueExact()`，在场对照。
- **severity=HIGH 理由**：资损向（系统性少付商户）+ 文档明示红线与合规问题。
- **对行证据**（head/src/main/java/com/acme/payhub/api/OpenApiPaymentController.java）：
  ```
  44:    public String refund(Long tenantId, Long merchantId, String orderNo,
  45:                         double amountYuan, String idempotencyKey) {
  46:        long amountFen = (long) (amountYuan * 100);
  ```

## 2. miss-clearing-currency-skip（holdout，JAVA，13647 字符 / 8 文件 head）

- **素材出处**：同一 payment patch 家族第二次无害化（独立副本、com.acme.clearing 域、
  文件结构与命名均不同），回植点换为 **P6 币种漏校验**。
- **缺陷一句话**：新增 InstantClearingService.submitInstant 接收 currency 后未做任何
  币种校验即入账（base 的 ClearingService.submit 有 `!"CNY".equals(currency)` 拒绝；
  knowledge/clearing-rules.md 第 3 节红线明示"币种校验必须落在清分服务层"，含
  EVAL-2025-08 USD 按 1:1 入账事故）。判据在 knowledge 文档而非跨切片 diff。
- **severity=MEDIUM 理由**：当前上游仅 CNY，属潜伏性合规/资损敞口，非即时触发。
- **对行证据**（head/src/main/java/com/acme/clearing/service/InstantClearingService.java；
  区间 31-51 = 方法签名到 currency 原样入构造，全程无币种校验）：
  ```
  31:    public ClearingRecord submitInstant(Long tenantId, Long merchantId, String idempotencyKey,
  32:                                        long grossFen, String currency) {
  50:        ClearingRecord record = new ClearingRecord(
  51:                tenantId, merchantId, idempotencyKey, grossFen, feeFen, currency);
  ```

## 3. miss-vcs-runner-nullcheck（development，JAVA，10765 字符 / 5 文件 head）

- **素材出处**：本仓 r5 批B 提交 b0e7514（GitCliService 512→328 抽出包私有
  GitCommandRunner）的类拆分形态脱敏改编（com.acme.devhub，VcsCliService →
  VcsProcessRunner，askpass/超时/抽干/脱敏语义同形不同文）。
- **缺陷一句话**：搬家时 addTrustedDirectoryOptions 的判空次序被悄悄反转——先
  `remoteUrl.replace(...)` 解引用、后 null 判断（base 中判空在前）；同方法内的
  null 守卫与 RepoHandle Javadoc"本地裸镜像返回 null"证明 null 是预期输入，
  证据完整落在缺陷文件内。
- **severity=MEDIUM 理由**：仅"本地裸镜像仓库"路径触发 NPE，非全量崩溃。
- **对行证据**（head/src/main/java/com/acme/devhub/vcs/VcsProcessRunner.java）：
  ```
  97:        String normalized = remoteUrl.replace('\\', '/').replaceAll("/+$", "");
  98:        if (remoteUrl == null || remoteUrl.isBlank()
  99:                || remoteUrl.toLowerCase().startsWith("http") || remoteUrl.startsWith("git@")) {
  100:            return;
  101:        }
  ```

## 4. miss-pump-cap-dropped（holdout，JAVA，8549 字符 / 5 文件 head）

- **素材出处**：本仓 r5 批C-1 提交 955a088（类按层归位、行为零变化）的 move-only
  形态脱敏改编（com.acme.toolbench：ToolOutputCollector→procio.ProcessOutputPump、
  TransientToolException→common.error 双搬家，Javadoc 自称"行为零变化"）。
- **缺陷一句话**：搬家时抽干循环里的容量上限分支被悄悄丢掉——`captured.append`
  变成无条件执行；MAX_CAPTURE_CHARS 常量与 truncated 字段仍在（声明了从未使用/
  从未置位），类 Javadoc 仍承诺"超上限停止追加"，同文件内自相矛盾即为证据；
  超大工具输出（SBOM/依赖树）可撑爆堆。
- **severity=MEDIUM 理由**：需外部工具产出超大输出才触发，属资源失控而非必现故障。
- **对行证据**（head/src/main/java/com/acme/toolbench/procio/ProcessOutputPump.java；
  区间 19-31 覆盖孤儿常量、失效字段与无界追加三个可指认锚点）：
  ```
  19:    private static final int MAX_CAPTURE_CHARS = 2_000_000;
  23:    private volatile boolean truncated;
  30:                while ((read = in.read(chunk)) >= 0) {
  31:                    captured.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
  ```
  （base 同位置为 `if (captured.length() < MAX_CAPTURE_CHARS) {...} else { truncated = true; }`）

## 5. miss-template-share-authz（development，TYPESCRIPT，9233 字符 / 5 文件 head）

- **素材出处**：自造"功能迭代大 PR"（构造路线 3）：报表模板中心新增分享/批量导出/
  删除共 5 个新入口 + 审计接线。
- **缺陷一句话**：新增 DELETE /templates/:id（removeTemplate）只做 requireUser 未做
  assertOwnership，任意登录用户可按 id 删除他人模板（连带清掉分享授权）；同文件
  getTemplate/updateTemplate/shareTemplate/revokeShare/bulkExport 全部调用
  assertOwnership，auth.ts 注释明示"所有按 id 操作单个模板的入口都必须调用"，
  在场对照即证据。
- **severity=HIGH 理由**：未授权破坏性写操作（删除 + 授权连带清理），影响他人数据。
- **对行证据**（head/src/templates.ts）：
  ```
  81:  async removeTemplate(ctx: RequestContext): Promise<void> {
  82:    const user = requireUser(ctx);
  83:    const template = await this.loadOr404(ctx.params.id);
  84:    await this.grants.removeAllForTemplate(template.id);
  85:    await this.store.deleteById(template.id);
  ```
  （83 与 84 之间缺 `assertOwnership(user, template)`）

## 6. miss-ledger-import-leak（development，JAVA，11974 字符 / 7 文件 head）

- **素材出处**：自造"功能迭代大 PR"（构造路线 3，工程质量向）：台账 CSV 批量导入
  （入口门槛/解析/校验/分批入库四件套），资源泄漏形态取 java-sql-resource-leak
  既有手法换资源类型（文件流）。
- **缺陷一句话**：LedgerCsvParser.detectCharset 用 `Files.newInputStream` 打开文件后
  三条 return 路径均不关闭（无 try-with-resources），每次导入泄漏一个文件句柄；
  紧邻的 parse 方法正确使用 try-with-resources，在场对照即证据。
- **severity=MEDIUM 理由**：句柄随导入次数累积，长期运行耗尽 fd，非立即故障。
- **对行证据**（head/src/main/java/com/acme/ledger/imports/LedgerCsvParser.java；
  区间 32-46 = detectCharset 全方法，33 打开、38/43/46 三处未关闭返回）：
  ```
  32:    Charset detectCharset(Path file) throws IOException {
  33:        InputStream in = Files.newInputStream(file);
  38:            return StandardCharsets.UTF_8;
  43:            return StandardCharsets.UTF_8;
  46:        return decoded.contains("�") ? Charset.forName("GBK") : StandardCharsets.UTF_8;
  ```

## split 配额复点

development 4：miss-payhub-refund-cast / miss-vcs-runner-nullcheck /
miss-template-share-authz / miss-ledger-import-leak；holdout 2：
miss-clearing-currency-skip / miss-pump-cap-dropped。≥2 holdout 达标。

## knowledge 文档

- miss-payhub-refund-cast/knowledge/amount-rules.md（约 0.9k 字符）
- miss-clearing-currency-skip/knowledge/clearing-rules.md（约 0.9k 字符）
- 其余 4 例缺陷不依赖文档判据，未带 knowledge/。
