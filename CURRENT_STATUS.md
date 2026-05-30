# 当前落地状态

更新时间：2026-05-29

## 已完成

- 后端 Spring Boot 工程骨架。
- 用户注册、登录、Token 鉴权。
- 项目创建、列表、详情、修改。
- Git 仓库绑定。
- Git accessToken AES-GCM 加密存储，接口不返回明文 Token。
- 使用本机 Git CLI 拉取 commit 和 diff，HTTPS 私有仓库可通过加密 Token 注入 Git 认证头。
- 知识库文档上传、切片、入库。
- 内存 RAG 检索与 pgvector 检索实现边界。
- Mock Embedding 与 OpenAI-compatible Embedding 客户端。
- RabbitMQ exchange / queue / publisher / consumer 配置。
- MQ 任务日志、失败重试、死信投递逻辑。
- 开发环境默认 inline 审查，无需 RabbitMQ 即可跑通。
- Mock AI 审查，支持识别权限风险、SQL 注入、空指针、业务规则风险。
- OpenAI-compatible Chat 审查客户端。
- AI 调用日志 `ai_call_log`，记录 embedding/review 调用类型、模型、耗时、输入输出长度、状态和错误。
- 审查任务、审查报告、问题列表保存和查询。
- Issue 人工反馈模块。
- Vue 3 前端工作台。
- FastAPI 模型服务，支持 TF-IDF + LogisticRegression 训练、模型加载、预测和规则兜底。
- Java 审查链路可调用 FastAPI 模型服务，模型风险预判会进入 AI 审查上下文并写入 `MODEL_RISK` 日志。
- 项目正式命名为 RepoSage 智能代码审查平台。
- Docker Compose、Nginx、Dockerfile 配置。
- 生产环境 PostgreSQL 幂等 schema 初始化脚本，配合 `ddl-auto=validate` 校验表结构。
- Nginx 已配置前端容器代理、后端 `/api` 代理和 `/actuator` 健康检查代理。
- 演示仓库 `demo-repos/mall-order-service`，包含两次提交。
- 本地开发手册、服务器部署手册、答辩脚本、风险清单、断点续聊 Prompt、API 冒烟用例。
- 界面与功能测试报告 `docs/17_界面与功能测试报告.md`。
- PowerShell 后端冒烟脚本 `scripts/smoke-backend.ps1`。
- PowerShell 一键本地验收脚本 `scripts/verify-local.ps1`。
- 服务器部署操作手册已更新：`docs/12_服务器部署与演示手册.md`。
- 后端测试用例：Token、Mock AI、Embedding JSON、认证后项目创建集成测试。
- 后端测试用例：CryptoService 加解密、仓库 Token 加密入库集成测试。
- 模型服务训练脚本 `model-service/scripts/train_model.py`、样例数据和本地模型文件。
- 根目录 README 已按 RepoSage 项目名重写。

## 已验证

后端构建：

```text
cd backend
mvn -s .mvn/settings.xml -q -DskipTests package
```

结果：通过。

前端构建：

```text
cd frontend
npm run build
```

结果：通过。

前端页面与代理联调：

```text
npm run dev -- --host 127.0.0.1
GET http://127.0.0.1:5173
POST http://127.0.0.1:5173/api/auth/register
```

结果：前端页面返回 200，页面标题包含 RepoSage，Vite `/api` 代理注册成功。

后端自动化测试：

```text
cd backend
mvn -s .mvn/settings.xml test
```

结果：12 个测试通过，覆盖 Token、Mock AI、Embedding JSON、CryptoService、认证后项目创建、未登录拦截、仓库 Token 加密入库。

后端完整冒烟链路：

```text
健康检查 -> 注册 -> 创建项目 -> 绑定演示仓库 -> 上传知识库文档 -> RAG 检索 -> 触发审查 -> 查看报告 -> 提交反馈
```

结果：

```text
Health : UP
OverallRisk : HIGH
Issue : AUTH_RISK
FeedbackId : 1
FeedbackCount : 1
MqLogCount : 0，dev inline 模式未经过 RabbitMQ
AiLogCount : 4
```

最新冒烟结果：

```text
Health           : UP
RepositoryStatus : ACTIVE
KnowledgeStatus  : INDEXED
SearchMatches    : 1
TaskStatus       : SUCCESS
OverallRisk      : HIGH
FirstIssue       : AUTH_RISK
FeedbackId       : 1
MqLogCount       : 0
AiLogCount       : 4
```

模型服务接入后端链路专项冒烟：

```text
MODEL_SERVICE_ENABLED=true
MODEL_SERVICE_URL=http://127.0.0.1:18000
TaskStatus : SUCCESS
AiLogCount : 5
AiLogTypes : CHAT_REVIEW,EMBEDDING_INDEX,EMBEDDING_SEARCH,MODEL_RISK
```

一键本地验收：

```text
.\scripts\verify-local.ps1
```

结果：

```text
Backend tests       : PASS
Frontend build      : PASS
Model service check : PASS
Backend smoke       : PASS
Docker availability : SKIP，当前机器无 docker 命令
```

模型服务训练：

```text
cd model-service
python scripts/train_model.py --version local-demo-v1
```

结果：生成 `models/risk_classifier.joblib`，训练集样例可跑通，模型预测返回 `source=trained-model`。

界面优化：

- 项目已统一命名为 RepoSage。
- 仓库页面增加“填入演示仓库”按钮。
- 选择 Commit 后自动填入审查表单的 commit/base commit。
- 触发审查后自动加载最新任务和报告。
- 前端按钮操作增加错误提示兜底。
- 使用浏览器检查桌面和 390px 移动端页面，标题、登录页、导航均正常，无横向溢出。
- 移动端导航优化为两列，减少首屏侧边栏高度。

## 当前限制

- Docker 未安装或不在 PATH，尚未在本机验证容器化部署。
- 生产级 pgvector 与 RabbitMQ 异步链路仍需在 Docker/服务器中最终验收。
- AI 调用默认是 Mock，真实大模型 API 客户端已实现，但尚未用真实 Key 实测。
- 自动化测试已起步，但还需要继续覆盖 Git、Knowledge、ReviewProcessor、MQ 重试和反馈边界。

## 下一步建议

1. 继续补后端自动化测试：Git、Knowledge、RAG、ReviewProcessor、MQ 重试、Feedback 边界。
2. 安装 Docker 或使用云服务器验证 `deploy/docker-compose.yml`。
3. 准备真实大模型 API Key，完成一次 OpenAI-compatible 调用验收。
4. 在服务器上按 `docs/12_服务器部署与演示手册.md` 完成 Docker Compose 验收。
5. 继续做界面测试和交互优化。
