# 新对话衔接 Prompt

## 使用方式

如果长对话后模型开始遗忘上下文，复制下面整段内容到新对话窗口。新模型应当直接承接当前项目状态，不需要重新解释项目背景。

## 衔接 Prompt

```text
你现在接手一个 Java + AI 实习展示项目，请不要从零开始问背景，直接按下面上下文继续推进。

项目根目录：F:\202605New
项目名称：RepoSage 智能代码审查平台
项目定位：面向实习展示的 Java 后端 + AI/RAG + RabbitMQ 项目，可本地运行，也计划部署到服务器。

核心目标：
做一个基于 Spring Boot、RabbitMQ、RAG 和大模型接口的代码仓库智能审查平台。用户可以创建项目、绑定 Git 仓库、上传项目知识库文档、选择 Commit 触发 AI 审查。系统解析 Git Diff，通过 RAG 检索相关规范，再调用 AI 生成结构化审查报告，并支持问题反馈。

技术栈：
- 后端：Java 17、Spring Boot 3.5.8、Spring Security、Spring Data JPA、H2 dev、PostgreSQL prod、RabbitMQ。
- 前端：Vue 3、Vite。
- RAG：文档上传、切片、embedding、内存检索 dev、pgvector prod。
- AI：Mock AI Reviewer + OpenAI-compatible chat/embedding client。
- 模型服务：FastAPI + scikit-learn，已支持 TF-IDF + LogisticRegression 轻量训练、模型加载、预测和规则兜底。
- 部署：Docker Compose、Nginx、PostgreSQL + pgvector、RabbitMQ。

已经完成的主要代码：
- backend Spring Boot 工程。
- 注册、登录、Token 鉴权。
- 项目管理。
- Git 仓库绑定、commit 列表、commit diff，accessToken 已做 AES-GCM 加密存储，并已用于 HTTPS 私有仓库 clone/fetch/pull 的 Git 认证头。
- 知识库文档上传、切片、embedding、RAG 检索。
- Mock AI 审查，支持识别 AUTH_RISK、SQL_INJECTION、NULL_POINTER、BUSINESS_RULE_RISK。
- OpenAI-compatible ReviewClient 和 EmbeddingClient 边界。
- AI 调用日志：记录 embedding/review 调用类型、模型、耗时、输入输出长度、状态和错误。
- 审查任务、报告、issue 保存查询。
- RabbitMQ exchange/queue/publisher/consumer，支持重试、死信、MQ 日志。
- 反馈模块：TRUE_POSITIVE、FALSE_POSITIVE、NEED_DISCUSSION。
- Vue 前端工作台：登录注册、项目、仓库、知识库、审查、报告、反馈、MQ 日志。
- model-service 已有训练脚本、样例数据、`risk_classifier.joblib` 模型文件、`/model/status` 和 `/predict`，并已接入 Java 审查链路生成 `MODEL_RISK` 风险预判。
- deploy/docker-compose.yml、backend/frontend Dockerfile、Nginx 配置、init.sql。
- demo-repos/mall-order-service 是一个本地 Git 演示仓库，包含两次提交和安全/业务规范文档。
- scripts/smoke-backend.ps1 可以在后端启动后自动跑核心 API 冒烟链路。

已经生成的文档：
- 代码仓库智能审查平台_需求规格说明书.md
- docs/00_开发前准备清单.md
- docs/01_系统架构设计说明书.md
- docs/02_数据库设计说明书.md
- docs/03_接口设计文档.md
- docs/04_MQ与异步任务设计.md
- docs/05_RAG与AI审查设计.md
- docs/06_开发任务拆解与里程碑计划.md
- docs/07_演示数据与样例仓库设计.md
- docs/08_部署环境与配置清单.md
- docs/09_测试与验收计划.md
- docs/10_项目初始化脚手架方案.md
- docs/11_本地开发与联调手册.md
- docs/12_服务器部署与演示手册.md
- docs/13_实习展示与答辩脚本.md
- docs/14_开发缺口与风险清单.md
- docs/15_新对话衔接Prompt.md
- docs/16_API冒烟测试用例.md

重要本地命令：
后端构建必须使用项目 Maven settings，否则全局 Maven 本地仓库可能无权限：
cd F:\202605New\backend
mvn -s .mvn\settings.xml -q -DskipTests package

后端启动：
cd F:\202605New\backend
mvn -s .mvn\settings.xml spring-boot:run

前端依赖安装：
cd F:\202605New\frontend
npm install --cache .npm-cache

前端启动：
cd F:\202605New\frontend
npm run dev

当前已验证：
- 后端 mvn package 通过。
- 后端 mvn test 通过，当前 12 个测试覆盖 Token、Mock AI、Embedding JSON、CryptoService、认证后项目创建、未登录拦截、仓库 Token 加密入库。
- 前端 npm run build 通过。
- 后端完整冒烟链路通过：health -> register -> project -> bind demo repo -> upload knowledge -> search -> create review task -> report -> feedback。
- 冒烟结果包括：Health UP、task SUCCESS、overallRisk HIGH、issue AUTH_RISK、feedback 保存成功、AiLogCount 4。
- 模型服务训练通过：`python scripts/train_model.py --version local-demo-v1` 生成 `model-service/models/risk_classifier.joblib`，预测返回 `source=trained-model`。

当前限制：
- 当前机器 Docker 未安装或不在 PATH，Docker Compose 尚未本机验证；部署配置已调整为独立 Nginx 反代 frontend 容器和 backend `/api`、`/actuator`。
- 真实大模型 API 尚未用真实 Key 实测。
- pgvector 生产模式代码和部署配置已准备，但还需要在 Docker/服务器上验收。
- 模型服务已完成轻量训练闭环并接入后端审查主流程，但样例数据规模仍偏小。
- Git accessToken 已加密存储并用于 HTTPS Git 认证头，真实私有仓库仍需服务器验收。
- 自动化测试已起步，但 Git、Knowledge、ReviewProcessor、MQ 重试和反馈边界仍需继续覆盖。

下一步优先级：
1. 继续补后端自动化测试：Git、Knowledge、RAG、ReviewProcessor、MQ 重试、Feedback 边界。
2. 继续进行界面和功能测试优化，重点检查前端工作台的完整演示流程。
3. 在有 Docker 的机器或云服务器验证 deploy/docker-compose.yml。
4. 准备真实大模型 API Key，完成一次 OpenAI-compatible 调用验收。
5. 在服务器上按 `docs/12_服务器部署与演示手册.md` 完成 Docker Compose 验收。
6. 整理最终答辩截图和演示材料。

协作要求：
- 用户希望你主动继续准备和落地，不要只给建议。
- 修改文件用 apply_patch。
- 不要回滚用户已有改动。
- 中文回答，简洁但要具体。
- 如果需要搜索/读源码，优先使用 rg。
- 当前任务一般不需要再问用户，能做就继续做。
```
