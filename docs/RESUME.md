# 简历写法与掌握标准

当前代码与练习以 [12 项能力路线](LEARNING_PATH.md) 为准，所有成绩应引用 [验证记录](VERIFICATION.md)。只有自己理解并能复现后，才把相应内容作为个人项目经验介绍。

## 项目名称

Java 智能设备售后 Agent（个人实战项目）

## 技术栈

Java 17 / Spring Boot 4 / Spring AI 2 / DeepSeek / PostgreSQL / pgvector / Redis / JPA / SSE / Micrometer / Docker Compose

## 简历项目描述（掌握代码后可用）

> 面向智能设备售后场景设计并实现 RAG + Tool Calling 智能体应用，支持产品知识问答、设备/耗材/工单查询以及带确认的工单创建；通过 Spring Profile 解耦确定性测试应答器与 DeepSeek 对话模型。
>
> 实现知识文档语义切片、向量化、topK 检索、来源引用及增删改，使用 pgvector 持久化向量，并通过 tenant metadata filter 与跨租户诱饵用例验证知识隔离；使用 Redis 保存带 TTL 和窗口限制的多轮会话。
>
> 将租户、用户与确认状态通过服务端 ToolContext 传递，Tool 内按 tenant + owner 二次鉴权；针对写工具设计“显式确认 + 幂等键 + 数据库唯一约束”，12 路并发测试下重复请求返回同一工单 ID。
>
> 为 Agent 增加 30 秒推理预算、单轮最多 8 次工具调用、有限重试、熔断、明确降级与 SSE；使用本地真实 MiniLM 向量和 BM25 融合重排，构建 50 条知识题和 10 条工具轨迹评测；全量 44 项自动化测试通过。

上面 44 项为 2026-10-12 的历史结果；2026-10-13 并发增强后新增验证以 [并发记录](CONCURRENCY.md) 为准。数字指测试方法，不是测试类；离线评测是小规模样本，不要描述成线上业务效果，不宣称回答 100% 正确。

掌握并发增强后可补充：实现 Redis Lua 全局/租户/用户准入与滑动窗口、会话续租及原子历史写入保护；通过 PostgreSQL 事务锁与同库事务保障多实例知识更新，验证过载返回 429、额度回收及故障回滚。不要把本地替身负载测试描述成真实模型生产吞吐。

## 更短的简历版本

> 基于 Spring AI 2 + DeepSeek 开发智能售后 Agent，整合 RAG、Tool Calling、Redis Memory、pgvector 与 SSE；实现 tenant/owner 双重鉴权、写 Tool 二次确认及唯一键幂等，完成 50 条 RAG 回归集和 12 路并发重复写验证，并接入超时、工具调用上限及 Prometheus 指标。

## 在掌握之前不要写的词

- “负责公司核心 AI 平台”：这是个人项目，不是工作经历。
- “百万级知识库、99.9% SLA、准确率提升 30%”：项目没有这些数据。
- “精通 LangGraph、多 Agent”：本项目是单 Agent 工具循环。
- “生产落地 Docker/K8s”：当前环境未做容器运行验证，也没有 K8s。
- “完全解决幻觉/越权”：工程只能降低风险并建立边界。

## 你需要达到的四档水平

### A. 看懂

能画出 Controller -> Agent -> Tool/RAG -> DB -> Answer 的链路。这个程度还不建议写“熟练智能体开发”。

### B. 能改（最低简历标准）

你应该亲手完成以下修改并通过测试：

1. 新增一个“查询保修期”的只读 Tool。
2. 新增一篇保修知识文档和至少 10 条评测问题。
3. 给会话历史增加“清空会话”接口，并做 tenant/user 隔离测试。
4. 改一个 Prompt 规则，并比较修改前后的评测结果。

### C. 能排错（面试有说服力）

不看答案解释并复现：事务自调用为何失效；先查再插为何不幂等；为什么关掉 chat 后 OpenAI Starter 仍要 Key；为什么 source ID 不一致会让召回评测变成 0；为什么跨租户过滤不能写在 Prompt 里。

### D. 能演进（可以主打 Java AI 应用）

能解释并复现当前真实 Embedding、PostgreSQL/Redis、BM25 重排、引用轨迹、重试熔断；进一步实现 Testcontainers、独立词法候选召回、限流和知识导入补偿，并用评测数据解释参数取舍。

## 建议你亲手重写的顺序

不要一上来整仓库照抄。新建一个空项目，按下面顺序重写，每一步都提交一次 Git：

1. Device/Ticket + 普通 REST API，先不用 AI。
2. 加 `AiResponder` 和一个最小聊天接口。
3. 加设备查询 Tool，并故意做一次越权测试。
4. 加工单写 Tool，先写出重复工单 Bug，再用唯一键修复。
5. 加 SimpleVectorStore、两篇文档、10 条检索问题。
6. 加 tenant metadata filter 和诱饵数据测试。
7. 加 Redis Memory，再验证同 conversationId 不串用户。
8. 加 SSE、总超时和工具次数限制。
9. 加 DeepSeek Profile，不改变业务层。
10. 加 pgvector、指标、50 条评测和部署配置。

完成后，你不是“记住了这个项目”，而是亲手经历了需求拆分、Bug、修复和验证。面试官换一个业务场景，你也能迁移思路。

## 面试前自测清单

以下问题每个都能在 90 秒内讲清，才适合放简历：

- 模型如何知道有哪些工具？一次 Tool 循环有哪些消息？
- ToolContext 与模型生成的参数有什么区别？
- 为什么资源归属校验要在每个 Tool 内部做？
- 文档如何切片、更新、删除？向量为何不能混用模型版本？
- topK、threshold、chunkSize 怎么通过评测调整？
- Redis 会话 key 如何设计？怎样限制上下文增长？
- 创建工单的幂等为什么要唯一键？`REQUIRES_NEW` 为什么拆 Bean？
- Agent 超时、HTTP 超时、工具次数限制分别防什么？
- 50 条数据集测到了什么，没测到什么？
- 如果模型或 Redis 挂了，系统怎样降级？

完整回答参考 [面试指南](INTERVIEW_GUIDE.md)，但最终要换成你自己的语言。
