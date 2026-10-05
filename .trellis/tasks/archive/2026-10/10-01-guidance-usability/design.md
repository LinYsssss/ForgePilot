# 技术设计

## 既有路径

ImplementationGuidanceService 读取当前需求/AC，调用 AiGateway.embed，按项目/需求召回最多 8 条知识，调用一次 chat；输出直接返回，不保存回答。RequirementDetailPage 显示结果。复用这条路径，不创建第二套运行机制。

## 输出和兼容

模型 schema 新增必需的 summary（非空白字符串）和 questions（字符串数组），原 checklist/rules/risks 不变。Prompt 要求每步写动作、适用 AC、验证办法，资料不足列待确认，不虚构已存在的代码或文件；无问题的栏目允许空数组。

DTO 追加 summary/questions/guidanceVersion，服务端常量 guidance-2，不由模型决定。前端将新字段视为可缺省，旧服务继续显示旧内容，不给缺失 questions 伪造“无待确认”。仅结构校验，复用 ai_malformed_result，不添加修复轮、语义解析或覆盖度统计。

## 页面与失效

本页 draftContent() 复用保存 payload；脏状态与 toDraft(savedRevision) 比对，统一可选正文的空字符串/null，避免未编辑就显示脏状态。

专用 guidance 请求序号在生成开始和失效时递增。load、成功保存、版本变化、卸载均调用 invalidateGuidance()。成功/失败/finally 只处理仍属当前序号的响应；成功还比对需求与修订。DRAFT 保存必须主动失效，因为 id 不变；服务端返回不同版本时清理建议并提示刷新。

未保存内容禁用生成并显示原因；不自动保存。重新生成期间保留旧建议，但有明显提示；失败保留旧建议并说明。保存后的旧结果不保留为当前结果。AppShell 已按 fullPath 重建页面，不改其机制，不做跨标签页实时同步。

## 阅读和导出

概览→待确认→编号步骤→规则/风险；正文纯文本，不增加 Markdown 渲染。知识来源与技术元数据使用原生 details，保留既有布局/令牌与长文本区域边界。

复制与下载使用同一本页私有 Markdown 格式化函数；下载 helper 从已有需求导出提取，继续清理 object URL。Clipboard 不可用/拒绝时提示改用下载，不新增旧式复制兼容。导出注明已保存修订、建议版本、未读取源码，不把相似度当正确率。

## 发布与回退

没有新存储，新旧字段为增量兼容；不回填历史。正式评测与 review-5 版本常量无关且不修改。提交、部署、真实模型样例另行确认；当前已有未提交工作保持。
