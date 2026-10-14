# 架构与实现指南

建议先完成 [12 项能力学习路线](LEARNING_PATH.md)。本文描述当前实现；实测数据以 [验证记录](VERIFICATION.md) 为准。Java 类均在 `com.example.supportagent` 包下。

2026-10-13 多人并发更新与新验证结果见 [并发设计](CONCURRENCY.md)。

## 模块边界

| 模块 | 核心类 | 职责 |
|---|---|---|
| web | ChatController、KnowledgeController、EvaluationController、SessionController | HTTP/SSE 接口、参数校验与管理权限 |
| security | CredentialRegistry、ApiKeyInterceptor、RequestIdentity | 凭据解析、可信身份、拒绝伪造身份请求头 |
| concurrency | AdmissionController、LeaseSupervisor | 实例与共享限额、用户滑动窗口、续租与失效处理 |
| agent | AgentChatService | 会话锁、历史裁剪、执行与记忆落盘 |
| agent | ReliableAgentRunner、AgentExecution、ModelCircuitBreaker | 总预算、取消、重试、熔断、工具调用预算和读缓存 |
| agent | SpringAiResponder、AgentPrompt | 模型调用、工具注册、提示词、引用、返回 usage |
| tool | AgentTools、TicketService、TicketWriter | 参数与权限校验、业务返回码、工单事务与幂等 |
| rag | KnowledgeService、KnowledgeReranker、KnowledgeManifest | 文档切片、索引、检索、重排、来源 |
| memory | ConversationMemory、ConversationGuard 的双实现 | 历史持久化和会话串行化 |
| evaluation | EvaluationService、EvaluationReport | 固定用例、工具事件验证、延迟与召回指标 |
| config | AiConfiguration、AsyncConfiguration、RedisSocketConfiguration | Profile 装配、MDC 传递、Windows Socket 兼容 |

## 同步请求走读

1. RequestTraceFilter 创建 traceId，并写入响应头与 MDC。
2. ApiKeyInterceptor 根据服务端注册的 API Key 获得租户、用户、角色。客户端同名身份头只能与其一致，不能改身份。
3. ChatController 校验 AgentRequest；AgentChatService 验证写请求必须带幂等键，先取得准入额度，再获取会话锁并读取历史；超额及时返回 429。
4. ReliableAgentRunner 检查熔断状态，通过线程池执行模型，并以 AgentExecution 的剩余截止时间等待。
5. SpringAiResponder 将系统提示、历史、问题、AgentTools 与可信 ToolContext 提交 Spring AI。框架执行模型返回的工具调用并继续请求模型。
6. AgentTools 通过 AgentExecution 计算调用次数、读缓存和截止时间；实际动作再做归属校验，业务拒绝返回 ToolResult。
7. 最终回答连同真实检索记录生成引用；一问一答原子写入会话；释放锁，清理轨迹并记录耗时。

设备未知不是系统故障：RESOURCE_UNAVAILABLE 回到模型，让它解释核对编号。数据库和未知系统异常保留完整堆栈，由可靠性层明确降级；身份错误为 401/403，非法请求为 400，同会话并发为 409。HTTP 200 的降级回答不表示业务成功，应读回答内容。

## 流式请求

POST /api/chat/stream 使用原生模型内容流；token 事件传片段，done 带 traceId 与 citations，部分回答后失败则 error 带 STREAM_INTERRUPTED。尚未输出 Token 的可恢复失败可以重试，已经输出后不重放。客户端断开或服务端超时会取消订阅、取消执行上下文并释放租约；只有成功完整回答才保存为一轮记忆。

当前内置网页使用同步 /api/chat。SSE 可用 requests.http 或 curl 验证；不能因存在 SSE 接口就声称网页正在使用流式渲染。

## RAG 与真实 Embedding

semantic Profile 使用本地 multilingual MiniLM ONNX 模型，384 维。模型文件和 tokenizer 由 prepare-embedding.ps1 固定版本下载，模型元数据保存在 data 中，不提交大文件。

切片按字符长度和标点边界执行；semantic 使用 120/20，默认演示使用 700/100。tokenizer 最大 128 Token，长问题可能被截断。查询先带 tenant_id 过滤，召回候选最多 12；BM25 与向量分数融合排序后最多返回 4 段。管理员诊断接口保留三个阶段数据以便分析。

旧 Hashing 和 MiniLM 即使都是 384 维，向量空间仍不同。semantic 使用独立表 support_vector_minilm_v1 与 minilm-v1 清单前缀；更换模型需重建索引。内置文档启动时重建，自建文档需要重新导入。

知识更新通过 KnowledgeWriteCoordinator 协调：本地分段锁限制同实例竞争，PostgreSQL 事务 advisory lock 协调同文档的跨实例更新；旧向量/清单删除与新内容写入处于同一数据库事务。SimpleVectorStore 不具备同样的回滚能力；未来使用独立向量服务需要补偿与版本发布机制。

## 可靠性与幂等的分工

请求内读缓存解决同一 Agent 反复查询；数据库唯一约束解决跨请求、跨进程并发写入。后者键为租户 + 幂等键，重放必须验证所有者和业务内容。缓存不替代事务，模型提示不替代校验。

可靠性执行器最多 2 次尝试，Spring AI 内部重试次数为 1，防止叠加。只有可恢复异常才考虑重试，确认写请求和已开始写入均不自动重放。熔断是进程内状态机，打开 30 秒后只放一个半开探测；过期调用的成功回调不能覆盖新的熔断状态。

执行上下文在工具入口和写入前再次检查取消与截止时间。它能阻止尚未开始的迟到写入，不能撤销已提交事务。降级消息分别表达“未提交”或“结果需核实”，不能把不确定的写入说成失败。

Redis 会话租约为模型预算加 10 秒，后台续租，释放时比较 token；容量额度独立续租。工具执行前核对租约，历史写入原子校验持有者。网络分区与已开始的事务仍需明确边界；HTTP、Redis、模型与数据库各层超时并非同一个概念。

## 安全与可观测性

查询身份不接受模型参数；文档中的指令不能修改服务端身份。管理员操作限本租户，知识检索按租户，设备与工单按租户加用户，会话再加 conversationId。报修确认是本次 HTTP 请求上的显式标记，不能由模型自行给出。

日志包含请求、尝试、工具返回码、检索耗时、工具事件、异常堆栈和同步返回 usage。业务摘要不记录密钥、完整请求头或原始对话。usage 的 scope=returned_response 仅表示框架返回响应的统计，不保证覆盖内部全部工具轮次；没有承诺完整账单或流式 Token 成本。

启动日志没有 traceId 属正常现象；Hibernate 的 unknown 连接池参数表示该日志接口未提供信息，不直接说明连接池损坏。Netty 扩展保活在当前系统不支持时走实际能力检测后使用 SO_KEEPALIVE，没有隐藏 WARN。

## 测试层次

- 单元/规则：缓存、预算、熔断时钟、超时取消、重试与 SSE 中断。
- HTTP/数据库集成：可信身份、越权、工单唯一约束与重放、异常响应。
- 协议集成：本地 HTTP 模型服务器返回真实 OpenAI-compatible 工具协议，Spring AI 实际执行工具循环。
- 外部依赖集成：通过开关运行真实 Redis 和本地 ONNX，避免默认测试隐含依赖。
- 真实模型评测：使用已配置 DeepSeek，记录数据集结果，和协议测试明确区分。

进一步加入新工具时，先确定业务权限与幂等契约，再注册模型描述，最后增加成功、拒绝、故障用例及离线评测问题。

