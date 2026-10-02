# guidance-3 本地验证记录

## 状态

本轮小范围实现与定向验证完成。尚未提交、推送、部署或调用真实模型；线上仍为 guidance-2。当前结果不能证明真实回答已经变短或不再漏约束。

## 实际改动

- ImplementationGuidanceService：仅修改指令、GUIDANCE_VERSION（guidance-3）及一条说明注释。
- 指令约束：只问阻碍实施的未知决策；不将未决前提写成确定事实；落实适用知识硬约束；合并重复步骤与验证，不用硬条数裁掉必要内容。不包含当前演示业务的特例。
- ImplementationGuidanceTest：更新两处版本期望，在现有 Prompt 捕获断言中确认通用要求进入请求；没有新增测试方法或测试类。
- API/ARCHITECTURE 仅同步源码版本与指令边界；没有更改当前部署记录或前端 fixture。

## 定向测试

沿用固定 JDK 21 容器入口执行：

```text
./mvnw -B -ntp -Dtest=ImplementationGuidanceTest test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 08:47 min
```

日志：`/tmp/claude-0/-root/af1b8a97-60d1-4e7c-95e0-b746a61f86d9/tasks/bxhqm16op.output`。

本次使用前台等待，工具在超过两分钟后转为可持续后台任务，正常结束；未重新运行无关的前端或整套后端测试。完整 CI 仍是后续发布门禁，不能把旧版全量结果算作 guidance-3 的全量验证。

## 边界核对

- 排除 INSTRUCTION、版本常量及新增说明注释后，生产服务与 HEAD 的其余文本完全相同；schema 文本逐字相同。
- 前端、DTO、迁移、evaluation 与 HEAD 无差异。
- verify-freeze、endpoint 订正 verify、diff 空白检查、变动文本编码检查及 Trellis 上下文清单验证全部通过。
- 两个源码/测试文件按路径排序并连接 `path + NUL + bytes + NUL` 的 SHA-256：`51565fc7a8ff3d37af8db1805fad38f79c0fb146c1fac017ac0768dd0253521e`。
- 规范复核：没有新增程序契约；继续沿用现有 AI/数据库规范，新增指令语义留在服务和架构说明中，不重复追加工程规范章节。

## 下一项

先按后续确认流程发布可测试的 guidance-3，再固定 3 条不同需求各生成一次：已知案例只作回归，另两条不用于本轮调优。检查阻塞问题是否适量、未决方案是否明确、知识硬约束是否落实，保留原始输入/输出与不足，不反复生成挑结果。真实浏览器、发布等待策略和 main 合并仍未在本轮执行。
