# RepoSage 智能代码审查平台

RepoSage 是一个面向实习展示和工程实践的 AI 代码仓库审查系统。它以 Git Commit Diff 为输入，结合项目知识库 RAG、RabbitMQ 异步任务、大模型接口和轻量风险分类模型，生成结构化审查报告、风险定位、修复建议和人工反馈记录。

## 项目亮点

- **Java 后端主线完整**：认证鉴权、项目管理、仓库绑定、知识库、审查任务、报告、反馈、日志。
- **AI + RAG 落地**：支持文档切片、embedding、内存检索、pgvector 生产模式和 OpenAI-compatible API。
- **消息队列实战**：RabbitMQ exchange、queue、consumer、retry、dead letter、MQ 日志。
- **安全设计**：Token 鉴权，Git accessToken 使用 AES-GCM 加密存储，接口不返回明文。
- **可观测性**：AI 调用日志记录 provider、model、耗时、输入输出长度、状态和错误。
- **自训练模型闭环**：FastAPI 模型服务支持 TF-IDF + LogisticRegression 训练、模型加载、预测和规则兜底，并可接入 Java 审查链路生成风险预判。
- **可部署**：提供 Docker Compose、Nginx、PostgreSQL + pgvector、RabbitMQ、前后端容器配置。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5、Spring Security、Spring Data JPA |
| 前端 | Vue 3、Vite |
| 数据库 | H2 开发环境、PostgreSQL 生产环境 |
| 向量检索 | 内存 embedding 检索、PostgreSQL + pgvector |
| 消息队列 | RabbitMQ |
| AI 接口 | Mock AI、OpenAI-compatible Chat/Embedding API |
| 轻量模型 | FastAPI、scikit-learn、joblib |
| 部署 | Docker Compose、Nginx |

## 目录结构

```text
backend        Spring Boot 后端
frontend       Vue 3 前端工作台
model-service 轻量风险分类模型服务
deploy         Docker Compose、Nginx、初始化 SQL
demo-repos     本地演示 Git 仓库
docs           需求、架构、部署、测试、答辩文档
scripts        一键冒烟脚本
```

## 本地启动

### 1. 启动后端

```text
cd backend
mvn -s .mvn/settings.xml spring-boot:run
```

开发环境默认使用 H2、Mock AI、内存 RAG 和 inline 审查，不需要先安装 PostgreSQL、RabbitMQ 或申请大模型 Key。

### 2. 启动前端

```text
cd frontend
npm install --cache .npm-cache
npm run dev
```

访问：

```text
http://localhost:5173
```

### 3. 训练并启动模型服务

```text
cd model-service
python scripts/train_model.py --version local-demo-v1
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

模型状态：

```text
GET http://localhost:8000/model/status
```

## 演示流程

1. 注册或登录。
2. 创建项目。
3. 绑定演示仓库：`demo-repos/mall-order-service`。
4. 上传知识库文档：`security-policy.md`、`order-flow.md`。
5. 查询 Commit，选择最新提交。
6. 触发代码审查任务。
7. 查看审查报告和问题详情。
8. 提交人工反馈。
9. 查看 AI 调用日志和 MQ 日志。

## 一键后端冒烟

后端启动后，在项目根目录执行：

```text
.\scripts\smoke-backend.ps1
```

脚本会自动完成注册、创建项目、绑定演示仓库、上传知识库、RAG 搜索、触发审查、查询报告、提交反馈、查询 MQ 日志和查询 AI 调用日志。

最近一次验证结果：

```text
Health           : UP
RepositoryStatus : ACTIVE
KnowledgeStatus  : INDEXED
TaskStatus       : SUCCESS
OverallRisk      : HIGH
FirstIssue       : AUTH_RISK
FeedbackId       : 1
AiLogCount       : 4
```

## 构建与测试

一键本地验收：

```text
.\scripts\verify-local.ps1
```

它会依次执行后端测试、前端构建、模型服务预测检查、后端完整冒烟，并检查 Docker 是否可用。当前机器没有 Docker 时会显示 `SKIP`，不影响本地功能验收。

后端测试：

```text
cd backend
mvn -s .mvn/settings.xml test
```

当前结果：12 个测试通过，覆盖 Token、Mock AI、Embedding JSON、CryptoService、项目集成流程和仓库 Token 加密入库。

前端构建：

```text
cd frontend
npm run build
```

模型服务语法与训练：

```text
cd model-service
python -m py_compile app/main.py scripts/train_model.py
python scripts/train_model.py --version local-demo-v1
```

## Docker 部署

```text
cd deploy
cp .env.example .env
docker compose up -d --build
```

如果代码是从 GitHub 新 clone 到服务器，先执行一次：

```text
bash scripts/init-demo-repo.sh
```

这会把 `demo-repos/mall-order-service` 初始化成真正的本地 Git 仓库，方便后端演示 clone 和 diff。

服务包括：

- PostgreSQL + pgvector
- RabbitMQ
- Spring Boot backend
- FastAPI model-service
- Vue frontend
- Nginx

生产环境启动时会执行 `backend/src/main/resources/db/schema-postgres.sql` 初始化 PostgreSQL 业务表和 pgvector 表，随后使用 `ddl-auto=validate` 校验实体结构。

当前本机 Docker 未安装或不在 PATH，容器化配置已准备，仍需要在服务器或安装 Docker 后做最终验收。

## 文档入口

| 文档 | 说明 |
| --- | --- |
| `代码仓库智能审查平台_需求规格说明书.md` | 需求规格说明 |
| `docs/README.md` | 文档索引 |
| `docs/11_本地开发与联调手册.md` | 本地启动与联调 |
| `docs/12_服务器部署与演示手册.md` | 服务器部署 |
| `docs/13_实习展示与答辩脚本.md` | 答辩讲稿 |
| `docs/16_API冒烟测试用例.md` | API 验收流程 |
| `docs/17_界面与功能测试报告.md` | 界面、功能、模型和构建验证结果 |

## 当前限制

- 真实大模型 API 客户端已实现，但还未用真实 Key 完成线上验收。
- Docker/服务器环境仍需在真实云服务器上最终验收。
- RabbitMQ 日志在 dev inline 模式下为空，生产模式 `REVIEW_INLINE=false` 后才会经过 MQ。
- HTTPS 私有仓库 token 已用于 Git clone/fetch/pull，SSH Key 方式仍可作为后续增强。
