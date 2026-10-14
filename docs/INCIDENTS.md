# 实际问题与复盘

本文保留早期版本的问题复盘。2026-10-12 完善后的当前架构与实测结果见 [实现指南](IMPLEMENTATION_GUIDE.md) 和 [验证记录](VERIFICATION.md)；历史日志、测试数量不代表当前状态。

这些问题是在把项目真正编译、运行测试时遇到的，不是为了故事效果虚构的。

## 1. Spring Boot 4 的 MockMvc 测试包发生迁移

现象：主代码能编译，测试编译时找不到旧的 `AutoConfigureMockMvc`。

原因：Boot 4 将 WebMVC 测试支持拆到更聚焦的模块，注解包名变为 `org.springframework.boot.webmvc.test.autoconfigure`。照抄 Boot 3 教程会失败。

处理：增加 test scope 的 `spring-boot-webmvc-test`，并更新 import。

面试价值：依赖升级不能只改版本号；要看迁移说明、模块拆分和编译错误，且用完整测试验证。

## 2. Java 17 的 ExecutorService 不能用于 try-with-resources

现象：并发幂等测试编译失败，提示 `ExecutorService` 不能转换为 `AutoCloseable`。

原因：把较新 Java 示例写法带到了 Java 17。项目目标运行时是 Java 17，必须按目标版本编译。

处理：显式 `try/finally`，在 finally 中调用 `shutdownNow()`。

面试价值：本地 JDK 能运行不代表目标 JDK 能编译，CI 要锁定 release 版本。

## 3. 关闭 chat 模型后，OpenAI Starter 仍因无密钥启动失败

现象：默认无密钥 Profile 启动失败，错误来自 Audio Speech 自动配置。

原因：Spring AI 2.0 的 OpenAI Starter 不只包含 Chat，还会按条件自动配置图像、审核、语音合成和语音转写；这些条件默认匹配。

处理：在默认与 AI Profile 中显式关闭所有未使用的模型类型，仅在 `ai` Profile 启用 chat。

面试价值：要会看 Spring Boot 条件评估报告和 `@ConditionalOnProperty`，不能看到 Bean 创建失败就随便塞一个假密钥。

## 4. 第一版 50 条 RAG 评测来源命中率为 0

现象：手工问 E03 可以返回正确内容，但自动评测 retrieval successRate=0。

原因：知识文档 metadata 使用 `.md` 路径，而评测集仍写旧的 `.txt` 路径。语义召回正确，但评测数据契约错误。

处理：统一 source ID；来源标识被视为稳定业务字段，不再依赖展示名称。

面试价值：评测失败不一定代表模型差，要分解“数据标签、召回、生成、评分器”四层定位。

## 5. 离线中文 Hash Embedding 对同义表达召回不稳定

现象：初版回答关键词命中率只有 52%，例如“联网”和“离线”、“滚刷”和“主刷”的字面重合不足。

原因：Hash n-gram 是可重复的测试替身，不理解真正语义。

处理：演示 Profile 增加少量可解释的领域同义词特征，并继续使用 50 条数据集做门禁；文档明确生产必须替换正式 Embedding。

面试价值：不能把测试替身包装成生产算法。离线稳定性与线上语义质量是两个目标，应该分 Profile 管理。

## 6. “先查询再插入”挡不住并发重复工单

现象：12 个线程携带同一幂等键同时创建工单，多个线程都可能在插入前查到“不存在”。

原因：check-then-act 存在竞态窗口。

处理：数据库建立 `(tenant_id, idempotency_key)` 唯一约束。插入放在 `REQUIRES_NEW` 事务；失败线程捕获唯一键冲突后查询已经提交的赢家，所有调用得到同一 ticketId。

面试价值：应用层判断用于减少冲突，数据库约束用于保证事实。分布式锁不是这里的必要前提。

## 7. 文档 chunk 映射只放内存，重启后无法删除旧向量

现象：最初用 `ConcurrentHashMap<documentKey, chunkIds>` 支持 upsert；服务重启后 Map 丢失，持久化 PgVector 中的旧 chunk 找不到。

处理：增加 `KnowledgeManifest` 表持久化 chunk ID 清单，更新和删除先读取清单。

剩余边界：向量库写入与清单表更新不是天然原子事务。生产应使用导入状态机、Outbox/任务表和定时补偿，避免中途失败留下孤儿向量。

## 8. Spring AOP 自调用会让 `REQUIRES_NEW` 失效

风险：如果 `TicketService` 的 public 方法通过 `this.insert()` 调用本类 `@Transactional(REQUIRES_NEW)` 方法，调用不经过代理，新的事务不会创建。

处理：把插入操作拆到 `TicketWriter` Bean，由 `TicketService` 跨 Bean 调用。

面试价值：Spring 声明式事务的本质是代理拦截；private 方法、self-invocation、非 Spring 管理对象都可能导致事务失效。

## 9. Maven 默认本地仓库目录不可写

现象：依赖下载阶段报 AccessDenied，而不是依赖不存在。

原因：当前机器全局 Maven 配置把仓库指向受限目录。

处理：项目附带 `maven-settings.xml`，把本地仓库放到工作目录，构建命令显式 `-s maven-settings.xml`。

面试价值：构建失败先区分网络、权限、仓库镜像和依赖坐标，不要盲目换版本。

## 10. 当前环境没有 Docker

现象：`docker` 命令不存在。

处理：完成 Dockerfile 与 Compose 配置，但诚实标记为“当前机器未做容器运行验证”；Java 默认 Profile 已通过编译、测试和接口验证。

面试价值：简历中要区分“实现”“测试验证”“生产落地”，不要把没发生的事情说成线上成果。

## 11. 默认 Profile 的健康检查被未使用的 Redis 拉成 DOWN

现象：业务 API 正常，但 `/actuator/health` 返回 DOWN，详情指向 localhost:6379。

原因：Redis Starter 在 classpath 中会自动注册连接工厂和健康探针；Memory Bean 虽然只有 `redis` Profile 才启用，默认健康探针并不知道这个业务选择。

处理：默认配置关闭 Redis health indicator，`application-redis.yml` 再显式打开。这样无 Redis 的本地模式健康，启用 Redis 的生产组合仍会真实反映依赖状态。

面试价值：健康检查应该反映当前部署必需的依赖，不能让一个未启用的可选组件影响 readiness。
