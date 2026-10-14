# 12 项能力学习路线

这份路线的目标是：能沿着一条请求解释全部架构，并能自己增加工具、导入知识、隔离用户、处理失败和验证效果。先把完整版运行起来，再按下面顺序读，不必一开始钻进所有框架源码。

所有 Java 路径均相对于 `src/main/java/com/example/supportagent/`，测试路径相对于 `src/test/java/com/example/supportagent/`。

## 先用 30 分钟看懂业务

读 [用户手册](USER_GUIDE.md)，依次发送：“E03 怎么处理”“查询 device-001 状态”“它的主刷还剩多少”“给 device-001 报修”。观察知识引用、真实设备数据和确认按钮。

接着只读这 6 个入口：`SupportAgentApplication` → `web/ChatController` → `agent/AgentChatService` → `agent/SpringAiResponder` → `tool/AgentTools` → `rag/KnowledgeService`。这就是应用入口、HTTP 入口、编排、模型、工具与知识链路。

业务数据来自项目种子设备，并未连接真实扫地机 IoT 平台。真实模型指真实调用 DeepSeek；真实工具指实际执行 Java 和查询数据库；两者都不代表种子设备是真实联网硬件。

## 1. 从零接入一个模型

**读：** `pom.xml`、`application-ai.yml`、`config/AiConfiguration`、`agent/AiResponder`、`agent/SpringAiResponder`、`agent/DemoAiResponder`。

Spring Boot Profile 决定装配哪个应答器。Spring AI ChatClient 携带系统提示、历史、用户问题、工具定义和服务端 ToolContext 发给兼容 Chat API 的模型。模型返回工具调用时，框架执行本地 Java 工具，再把结果发回模型，直到形成回答。

**做：** 无 ai 启动一次，再以 ai 启动，比较日志中的 responder。连接另一个兼容服务时设置 base URL、模型名、密钥，并先运行协议测试。Embedding 是另一类模型，不能因为对话 API 配通就宣称语义向量也配通。

**验收：** 能解释一个普通问答为何可能产生多次模型 HTTP 调用；能在缺失密钥时定位启动失败，而不是退回 Demo 冒充成功。对应 `agent/ModelTransportIntegrationTest`。

## 2. 开发查询 Tool 和写 Tool

**读：** `tool/AgentTools`、`ToolResult`、`TicketService`、`TicketWriter`、`domain/*Repository`。

查询工具示例为 `get_device_status`。它只接收设备编号，身份从 ToolContext 取，Repository 查询同时包含租户和所有者。写工具 `create_support_ticket` 在此基础上增加确认、幂等键、问题长度校验和独立事务。

**做：** 参照状态查询增加“查询设备型号”只读工具，明确输入、权限条件、输出和错误码。写工具练习先复用现有报修流程：未确认返回 CONFIRMATION_REQUIRED，确认且有幂等键才写入。

**验收：** 能说明“模型说已经创建”没有效力，只有数据库结果才有。对应 `tool/AgentToolsIntegrationTest`。

## 3. Tool 参数为什么不可信

模型输出是概率生成，可能看错设备号、被用户诱导、受到检索文档中的指令影响。JSON 格式正确只证明语法能解析，不能证明资源存在或用户有权操作。

**读：** `security/ApiKeyInterceptor`、`CredentialRegistry`，以及 `AgentTools.ownedDevice`、`identity`、`createSupportTicket`。

可信链为：凭据 → 服务端账户注册表 → RequestIdentity → ToolContext → SQL 归属条件。模型不能提供 tenantId、userId、role、confirmedWrite、idempotencyKey 作为权限依据。

**做：** 使用普通用户密钥加入 `X-Role: ADMIN`，应返回 403；查询 device-900 或不存在设备，统一得到 RESOURCE_UNAVAILABLE，不透露到底是不存在还是别人的设备。

**验收：** 能解释 Prompt 规则是行为引导，服务端校验才是安全边界。对应 `security/IdentityIsolationTest`、`tool/AgentToolsFailureTest`。

## 4. 实现 RAG 入库和检索

**读：** `web/KnowledgeController` → `rag/KnowledgeService` → `KnowledgeManifest` → `config/AiConfiguration`。

入库：管理员身份 → 校验文档 → 清理旧 chunks → 切片 → 添加 tenant_id 等元数据 → Embedding → VectorStore → 保存 chunk ID 清单。检索：问题向量 → 租户过滤 → 候选召回 → 重排 → TopK → 工具结果 → 模型组织答案并附引用。

**做：** 在 requests.http 第 6 条填管理员密钥，新增一篇独特 FAQ；第 10 条查看检索结果；修改同一 documentId 后重新检索，再用 DELETE 接口删除。另一租户不能搜出该文档。

**验收：** 解释为什么更新要清理旧切片，为什么切换向量模型要重新索引。semantic 使用 `support_vector_minilm_v1` 和独立清单命名空间，旧 Hashing 索引不会混入；以前自定义的文档需要重新入库。对应 `rag/KnowledgeServiceIntegrationTest`。

## 5. 解释切片、Embedding、TopK 和 Rerank

- **切片**：将长文档分成可检索的小段，尽量在标点处断开；重叠部分保留边界上下文。本项目 semantic 配置 120 字符、20 字符重叠，字符不等于 Token。
- **Embedding**：将文本变成可比较的数值向量。semantic 使用真实多语言 MiniLM、384 维和 128 Token 输入窗口；过长输入会截断。默认 Hashing 是测试替身。
- **TopK**：保留最相关的若干项；本项目先取候选 12，再给模型最多 4 段。阈值还会过滤低相关候选，所以可能少于 K。
- **Rerank**：对已召回候选重新排序。本项目以向量分数和 BM25 词法相关度融合，更重视 E03 等准确词；它不是另一个神经模型，也无法找回第一阶段没有召回的文档。

**读：** `KnowledgeService.split/inspect`、`KnowledgeReranker`、`application-semantic.yml`。

**做：** 对同一问题分别请求 search 的 `rerank:false/true`，比较 candidates、ranked、selected。运行 retrieval-baseline 与 retrieval 评测，对比命中率和 MRR，而不是只看一条看起来更顺眼的回答。

**验收：** 能解释 K 越大为何不一定越好、切片越长为何可能被截断、重排为何不能保证 100% 命中。对应 `SemanticEmbeddingIntegrationTest` 和 `KnowledgeRerankerTest`。

## 6. Redis 多轮会话

**读：** `memory/ConversationMemory`、`RedisConversationMemory`、`RedisConversationGuard`、`AgentChatService.history`。

Redis 键含租户、用户、conversationId。一问一答由 Lua 原子写入，避免并发时出现半轮对话；最多保留 100 条消息，TTL 7 天。发给模型时最多取 12 条并应用 12000 字符预算；没有声称是精确 Token 预算，也没有自动摘要。

会话锁以 SET NX + TTL 获取，自动续租，释放用 Lua 比较持有者 token。历史追加和持有者校验在同一个 Lua 中执行，防止旧请求写回。相同会话仍忙时返回 409，用户/全局额度先耗尽时返回 429。不同用户即使使用相同 conversationId，也不会共用历史或锁。

**做：** 同一 conversationId 连续提问后，重启应用再追问；开启新会话观察上下文分离。同时发两条同会话请求观察 409。

**验收：** 能解释锁过期后旧请求为什么不能删除新请求的锁。对应 `memory/RedisMemoryIntegrationTest`，需开启真实 Redis 测试。Base64 存储编码不是加密。

## 7. 租户和用户隔离

**读：** `CredentialRegistry`、`ApiKeyInterceptor`、设备和工单 Repository、RAG 元数据过滤、Redis 键。

权限不能只靠一处 Controller 检查。设备同时按租户和用户过滤；知识按租户过滤；工单查询与重放校验所有者；会话按租户、用户和会话三部分隔离。管理员可管理本租户知识，并不自动获得普通用户设备的所有权。

**做：** 用 .env.local 的另一租户密钥在连接设置切换用户，同样询问 device-001 应被拒绝。禁止通过编辑只读身份字段切换权限。

**验收：** 能列出 HTTP、Tool、数据库、向量库、Redis 五处隔离落点。对应 `IdentityIsolationTest`、`AgentToolsIntegrationTest`、`RedisMemoryIntegrationTest`。

## 8. 重复 Tool 调用和写操作幂等

**读：** `AgentExecution.call`、`TicketService.createIdempotently`、`TicketWriter`、`SupportTicket`。

读工具在单次请求内按工具名与参数缓存，模型重试时复用查询结果；缓存不跨请求、用户共享，开始写入时清空。重复调用仍消耗工具调用预算，避免无限循环。

写工具不靠内存缓存：数据库对 tenant_id + idempotency_key 唯一约束兜底，独立事务尝试插入；并发输家读取赢家结果，校验所有者、设备和故障描述。同键不同内容返回 IDEMPOTENCY_CONFLICT，同键不能偷看另一用户工单。

**做：** requests.http 中同一确认请求连发两次，得到相同工单 ID；保留同键修改问题，应冲突。模型在新请求改写故障描述也可能触发冲突，应核对原请求，而不是静默返回不相关工单。

**验收：** 能解释“先查再插入”为何不能解决并发，以及为什么幂等要验证请求内容。对应 `AgentExecutionTest` 和 `AgentToolsIntegrationTest` 中 12 路并发用例。

## 9. 超时、重试、熔断和降级

**读：** `ReliableAgentRunner`、`TransientFailureClassifier`、`ModelCircuitBreaker`、`SafeFallback`、`AgentExecution`。

总推理预算默认 30 秒、最多 2 次尝试、退避默认 200ms；仅可恢复错误（例如 429/503、IO、超时）符合重试分类，401 等不重试。已确认写请求或已经开始写入的请求不自动重放；底层 Spring AI 重试设为 1，避免两层相乘。

连续 3 次可恢复失败打开熔断 30 秒，再允许一个半开探测。状态按进程维护。SSE 仅在尚无 Token 时可重试；已输出片段后失败则发 error 事件，不重新拼接整段答案。

**做：** 运行 `ReliableAgentRunnerTest`，阅读“忽略中断的模型仍不能在超时后开始写工具”用例；阅读 `ModelCircuitBreakerTest` 的虚拟时钟，不用等 30 秒即可验证状态迁移。

**验收：** 超时不是事务回滚。写入已开始时，降级只能说明结果需核实并保留原幂等键，不能保证“未创建”。会话锁默认 TTL 40 秒并自动续租；容量租约默认 30 秒并续租，失效后拒绝继续执行。两者用途不同，进一步阅读 [并发设计](CONCURRENCY.md)。

## 10. 通过日志定位 Agent 调用过程

**读：** `web/RequestTraceFilter`、`config/AsyncConfiguration`、`ToolTraceStore`、`SpringAiResponder`、`AgentTools.loggedExecute`。

页面 TRACE、响应头 X-Trace-Id 和服务端日志用同一个请求编号。按编号查：HTTP → Agent → model attempt → Tool started/completed/rejected/failed → RAG → model usage → Agent tools → HTTP 状态与耗时。缓存命中、重试、熔断、降级也有日志。

业务拒绝给结构化错误码；不可预期异常打印堆栈，前端不暴露内部实现。启动日志没有请求，所以 traceId 为空是正常现象。同步 model usage 统计返回 ChatResponse 的 usage，标记 scope=returned_response；不是整个内部工具循环的精确账单，流式也未提供完整成本统计。

**做：** 故意查询不存在设备，再按页面 TRACE 找到 RESOURCE_UNAVAILABLE；对照 fault injection 测试定位实际异常根因。查看 /actuator/prometheus 中 support 前缀指标。

**验收：** 能区分业务拒绝、模型限流、数据库异常和降级，而不是所有情况都报 HTTP 500。

## 11. 建立离线评测集

**读：** `evaluation/EvaluationService`、`EvaluationReport`、`src/main/resources/evaluation/dataset.tsv` 和 `tool-dataset.tsv`。

50 条知识题包含问题、期望来源和关键词；10 条工具题包含问题、预期工具、返回码和关键词。检索模式记录来源命中、MRR、平均/P95 延迟；回答模式同时检查关键词和来源；工具模式检查真实执行事件，不只匹配回答文字。

**做：** 加入你实际遇到的失败问题，保留基线，修改召回参数后重跑。requests.http 提供 4 种模式；agent/tools 在 ai Profile 下调用外部模型，会产生费用。工具评测不授权写入。

**验收：** 能解释关键词评测不等于人工忠实度评分、样本小不代表泛化；拒绝正确但没有调用预期工具也会被严格工具用例判失败。报告保留实际失败样本，不修改标签掩盖问题。

可直接执行 `./scripts/evaluate-local.ps1` 生成本地检索报告；加 `-IncludeModel` 运行回答和工具评测。默认写入 `target/evaluation-results`，避免覆盖本次保留的基线证据。独立端口可通过 `-BaseUrl http://localhost:18082` 指定。

## 12. 清楚介绍完整项目

建议用下面顺序做一次 5 分钟讲解：

1. 业务：售后用户用自然语言查知识和自己的设备，经确认发起报修。
2. 模型的作用：理解与规划，选择工具、组织有依据的回答；不负责权限和事务。
3. 请求链路：Controller → 身份 → 会话锁/历史 → 可靠性执行器 → 模型工具循环 → 数据/RAG → 回答与记忆。
4. 难点：不信任模型参数、多租户隔离、并发幂等、超时后写入风险、模型与本地数据错误的区分。
5. 证据：自动化测试、真实 Redis/Embedding、真实模型离线报告与失败样本。
6. 边界：静态 API Key、本地熔断、非精确 Token 预算、独立向量服务需补偿、未做真实业务容量压测。

**最终练习：** 自己画架构图，增加一个只读工具，导入一篇知识，新增两个评测样本，制造一次可恢复模型失败并靠 traceId 定位。这些做完，才是你真正掌握了这 12 项，而不只是把项目跑起来。

