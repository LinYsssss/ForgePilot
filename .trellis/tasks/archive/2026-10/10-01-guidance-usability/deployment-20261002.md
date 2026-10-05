# review-5 / guidance-2 部署与检查（2026-10-02）

## 范围与结果

用户授权推送、部署并检查本次改动；另确认采用一个现有演示需求进行一次真实建议生成。全部串行执行，未合并 main，未批量重审 PR，也未重跑正式评测。

- 部署提交：`ff7c13fe7ab1d6c2656d379c30a559130d88013a`，分支 `fix/review-guidance-usability`。
- 业务提交：review-5 为 `3248ffd`，guidance-2 为 `b56f670`；其后提交为文档、归档与会话记录。
- [CI 36965359078](https://github.com/LinYsssss/ForgePilot/actions/runs/36965359078) 的 Backend verify、Frontend gates、Evaluation contract、Empty-stack Compose smoke 全部通过。
- 新后端/前端已运行，本地与公网 curl 健康检查均为 UP；数据库容器与业务记录数保持。

## 备份与回退

部署前没有 PENDING/RUNNING 审查和待处理知识文档。

- 备份：`/root/fp-demo-pre-deploy-20261002T051349Z.dump`，custom 格式，3,119,656 字节，文件权限 600。
- SHA-256：`d81382e6e126d094321f54a5ea1a223ecee58924e535a61b990eb274bf42f882`。
- 使用 pg_restore --list 成功读取目录并确认包含 TABLE DATA；**没有在生产库执行恢复，也不把目录读取称为完整恢复演练**。
- 旧镜像保留为 `fp-demo-backend:pre-guidance-20261002t051349z`、`fp-demo-frontend:pre-guidance-20261002t051349z`。
- 预部署元数据：`/root/fp-demo-deployment-20261002T051349Z.json`，不含凭据。

## 构建与容器更新

使用临时 systemd oneshot 作长时构建执行器，先 build backend，再 build frontend；避免单次工具调用时限中止构建。构建日志在 `/root/fp-demo-build-20261002T051349Z.log`；结束并验证后已停止该临时服务。

更新命令只针对应用服务，带 `--no-deps --no-build`，未执行 down 或任何删除卷操作。

| 服务 | 旧镜像 | 新镜像 |
|---|---|---|
| backend | `7525aeb480095dcbf4f6188d911893722e033eb514e257be227268beac7ac02f` | `df833b55d2ec6e9fbe7f3f929c92f8ef551fd973ae5be53db8cc222882af044e` |
| frontend | `b8d8f09fdefa67b29f640f07fc37acf18fc92df816b577fcbb188ecb59300630` | `ceaf913fddb050e05b5df1d8c73bc819999a8c88a38890a26cfd151ec2d56637` |

PostgreSQL 容器仍是 `8dd86d1a690a`，启动时间仍为 `2026-09-04T07:01:09Z`。Flyway 仍为 V14，共 14 条成功迁移；没有新迁移。

### 首次健康等待的异常

后端首次健康等待曾报告 unhealthy，随后一次直连出现 connection reset；不能把整段命令最终退出 0 当作每一步都成功。后续重新检查确认：

- Spring 启动耗时 179.74 秒，进程约 202.936 秒后就绪；早期健康探针先判为不健康。
- 后端与前端均 RestartCount=0、OOMKilled=false，后续连续五次容器探针成功。
- 后端直连、前端代理及公网 curl 均返回 UP。

本次未调整运行资源或健康检查配置。后续应评估启动等待窗口，避免慢启动被提前判失败。

## 数据与公网资产核对

部署及本次建议检查后，以下计数保持：

| 记录 | 前 / 后 |
|---|---:|
| review | 181 / 181 |
| finding | 374 / 374 |
| requirement | 42 / 42 |
| knowledge_document（部署后核对） | 37 / 37 |
| finding_event | 120 / 120 |

- 公网首页和本地首页均引用 `/assets/index-BFv5Vaeq.js`，两端 JS 字节相同。
- JS SHA-256：`53007caf662367e75694a7c6a9b374ed917717ea39ebe2cd6570f6ba99cf3196`。
- 公网资源包含新概览/待确认、复制、参考资料与生成状态文案。
- Python urllib 的一次公网探测返回 403；随后普通 curl 访问首页和健康接口均为 200。客户端差异原因未定位，未更改边缘安全规则，不把这一探测异常隐藏成全绿。
- 后部署元数据：`/root/fp-demo-post-deploy-20261002T051349Z.json`。

## 真实建议样例：仅一次生成

以现有测试账号 dev01 调用演示需求 REQ-3「仓库发货前置校验」，已保存修订 ID 19、v2。此处使用的是演示材料，不是真实企业发货事故数据。

- HTTP 200，耗时 **55.571 秒**，返回 `guidanceVersion=guidance-2`，需求与修订匹配。
- 内容：1 段概览、7 项待确认、9 项清单、4 条规则、3 条风险、6 个知识片段（来自 4 份文档）。
- 实际审计：一次 EMBEDDING SUCCESS（Qwen/Qwen3-Embedding-8B）、一次 IMPLEMENTATION_GUIDANCE SUCCESS（gpt-5.6-luna），没有重新生成以挑选更好结果。
- 需求仍为 IN_DEVELOPMENT、修订仍为 19，未改变负责人、代码、Review 或 Finding。
- 原始输入与输出仅保存于本机受限文件：
  - `/root/fp-guidance-check-20261002T051349Z-requirement.json`，SHA-256 `d9c47f65f2f456d620974b630ce6b6ef55e1da16314d11ff209bac9c4cda3dc5`。
  - `/root/fp-guidance-check-20261002T051349Z-answer.json`，SHA-256 `0183a4d785926990fc1aaba6a262a124349102dc61ee5570c36cef6d88824f57`。

### 内容检查

正面观察：这条样例给出了发货实施方向，清单明确提到 AC-1 至 AC-5，且包含具体检查办法；不再只有泛泛的“完善处理、补测试”。资料不足时确实返回待确认项。

仍存在的问题：

1. **偏长**：7 个问题、9 个步骤；部分待确认项未必阻塞开工，最后的测试步骤还重复前面各项验证，宜进一步区分优先级与压缩重复。
2. **确定性表达不一致**：是否必须让发货、记录、审计同事务被列为待确认，但概览和步骤又按同事务直接给出，应区分“建议方案”和“已确认约束”。
3. **知识约束未完整落实**：参考资料明确要求订单归属/授权校验，样例只问角色如何确定，没有明确把资源归属或授权校验纳入实施步骤。

这是一次功能烟测和人工内容检查，**不是建议准确率、开发效率或整体质量的评测**。没有据此修改 Prompt、扩大功能或继续反复生成。

## 审查详情只读检查

读取 review 149 成功：COMPLETED，4 条 Finding，notReported 数组可读，validation 返回 droppedFindings=0、correctedLines=0。其存储的 promptVersion 仍为 review-4：这是历史报告来源，不能因新代码上线而改写。本次未创建新的 Review，因此没有新一轮 review-5 模型效果结论。

## 尚未执行

- 真实浏览器点击、折叠、复制/下载和窄屏视觉验收：当前没有可用浏览器工具，未安装新依赖。HTTP/静态资源核对不替代浏览器实操。
- 批量 PR 重审、正式评测或新 holdout：未执行。
- 针对上述样例质量问题的下一轮 Prompt 修改：未执行，需另行确定范围。
- main 合并：未执行；本次只推送并部署修复分支的已验证提交。
