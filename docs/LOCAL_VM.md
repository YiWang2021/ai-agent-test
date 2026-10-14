# Windows IDEA + VMware 完整版运行

多人正式模式已另行准备：先运行 `./scripts/configure-production.ps1`，再运行 `./scripts/start-production.ps1`，网页改用 `.env.production.local` 中的普通用户凭据。原 IDEA 配置仍是可学习和评测的完整开发模式。并发参数、生产边界与面试说明见 [并发文档](CONCURRENCY.md)。

当前 Windows 运行 Java，虚拟机 192.168.9.131 运行 PostgreSQL:5432/support_agent 与 Redis:6379。现有数据库和 Redis 凭据保存在忽略提交的 .env.vm / IDEA 本地配置中，不在文档展示。

## 当前启动方法

1. 先启动虚拟机，确认 PostgreSQL 和 Redis 容器运行。
2. IDEA 重新加载 Maven，选择 **SupportAgent - VMware**。
3. 运行配置已使用 `ai,postgres,redis,semantic`，并指向项目准备的兼容 JDK。
4. 重启应用，访问 http://localhost:8080/ ；已运行的旧进程不会自动加载这些更改。

命令行可替代 IDEA：

```powershell
./scripts/start-local.ps1
# 8080 已有服务时，独立验证：
./scripts/start-local.ps1 -Port 18082
```

start-local 读取已有 IDEA 连接配置及 .env.local，默认完整模式。旧 start-vm.ps1 保留为仅启用 postgres,redis 的确定性演示入口，它不启用真实模型和语义向量。不要将两个脚本的模式混淆。

## 在新环境准备

```powershell
./scripts/prepare-java.ps1
./scripts/prepare-embedding.ps1
./scripts/configure-local.ps1
```

Java 仅安装到项目 data/runtime；use-project-java.ps1 在当前 PowerShell 进程设置 JAVA_HOME/PATH，不改全局设置。模型放在 data/models/multilingual-minilm。prepare 脚本下载固定版本并验证文件信息；首次本地推理还可能初始化其他原生库缓存，需要网络。

configure-local 创建管理员和另一租户密钥至 .env.local；已有键保留，更新 IDEA 的完整 Profile 与替代 JRE，但不覆盖已有 DeepSeek/数据库凭据。在新机器没有 IDEA 配置时，先通过环境变量配置下列连接参数：

| 环境变量 | 作用 |
|---|---|
| DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL | 外部对话服务，默认 DeepSeek 地址与 deepseek-chat |
| DB_URL / DB_USER / DB_PASSWORD | PostgreSQL JDBC 连接 |
| REDIS_HOST / REDIS_PORT / SPRING_DATA_REDIS_PASSWORD | Redis 连接 |
| SUPPORT_API_KEY | 普通用户访问密钥，默认 demo-key |
| SUPPORT_ADMIN_API_KEY / SUPPORT_OTHER_USER_API_KEY | 管理员和另一租户账户，空值表示不配置该账户 |

页面访问密钥和外部模型密钥用途不同。身份来自服务端账户绑定；不要把 DeepSeek 密钥填到网页。

## 常见日志

**Unknown channel option TCP_KEEP…**：原先由 Lettuce 尝试设置平台不支持的扩展保活参数引起。RedisSocketConfiguration 检测实际 JDK Socket 能力，不支持时使用标准 SO_KEEPALIVE；支持时保留扩展配置。这是修正配置，不是屏蔽日志。

**ONNX DLL initialization failed**：本机旧 Oracle JDK 17.0.12 自带 MSVC 14.36，与所用原生库存在兼容问题。项目准备的新 JDK 自带较新运行库，实际中文向量测试已通过；无需删除系统 DLL。参考 [ONNX Runtime 安装要求](https://onnxruntime.ai/docs/install/) 及 [官方仓库中相关 Windows/JDK 讨论](https://github.com/microsoft/onnxruntime/discussions/23971)。

**Database JDBC URL / dialect / version**：Hibernate 输出连接与 SQL 方言信息。READ_COMMITTED 是隔离级别；Autocommit、Minimum/Maximum pool size 的 undefined/unknown 表示这段日志没有读取到这些值，不是连接失败证据。连接失败应结合具体 SQLException、连接池日志和健康检查判断。

**traceId 为空**：启动阶段、后台初始化没有 HTTP 请求，所以正常。业务请求应按响应 X-Trace-Id 或页面 TRACE 搜索。

## 验证

```powershell
./scripts/verify-project.ps1 -WithRedis -WithSemantic
```

最新全量结果及真实模型评测见 [验证记录](VERIFICATION.md)。健康接口 UP 只说明依赖健康，不证明模型一定有额度或能够回答；模型能力需实际请求和评测验证。

## 虚拟机维护

```bash
cd ~/support-agent-infra
sudo docker compose ps
sudo docker compose up -d
sudo docker compose logs --tail 50
```

虚拟机服务使用数据卷；`docker compose down -v` 会删卷，不应当作普通重启。虚拟机 IP 改变时同步更新它的绑定配置、本机 .env.vm 和 IDEA 连接地址。semantic 使用独立向量表，业务设备和工单表保持原有数据；旧自建知识需重新导入语义索引。

