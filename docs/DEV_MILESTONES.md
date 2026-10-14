# dev 分支：从设备选择到报修记录

这轮开发解决首次体验时不知道设备编号，以及报修后只能通过聊天查询记录的问题。
沿着 Git 历史依次阅读以下六个里程碑，可以看到一条完整的功能开发过程。

| 里程碑 | 功能 | 主要入口 |
|---|---|---|
| 1 | 当前账号的设备列表，返回业务摘要而非整个实体 | DeviceController、DeviceRepository |
| 2 | 设备选择、状态摘要、快捷问题绑定所选设备 | index.html、app.js |
| 3 | 归属校验、分页、稳定排序的设备工单记录 | TicketController、SupportTicketRepository |
| 4 | 工单记录弹窗、上一页/下一页、刷新与空状态 | app.js、app.css |
| 5 | 输入框设备上下文、身份初始化、统一错误与追踪编号 | client.js、app.js |
| 6 | 数据隔离、分页与请求行为回归测试、体验说明 | DeviceWorkspaceTest、client.test.cjs |

## 在 IDEA 体验

1. 确认当前分支为 dev，重新构建并重启 Spring Boot。新增了 Java 接口，只刷新网页不够。
2. 打开 http://localhost:8080/，必要时 Ctrl+F5 强制刷新静态资源。
3. 使用自己的访问密钥；默认 demo-key 对应 tenant-a / user-001。页面从服务端读取账号身份。
4. 顶部选择 CleanBot X2（device-002），输入“查询当前状态和电量”。请求会自动带上所选设备编号。
5. 切回 CleanBot X1（device-001），点击耗材建议或申请报修快捷问题。切换设备会开启新对话。
6. 申请报修后核对确认内容，再点击提交。确认请求保留原设备、原消息和原幂等键。
7. 点击顶部“查看工单记录”，直接检查写入结果。每页 10 条，按创建时间倒序；时间相同时按 ID 倒序。
8. 报修响应超时，不代表创建失败。先查看工单记录，再决定是否重试原请求，避免重新输入造成重复工单。

明确输入其他 device 编号会保留用户指定的目标；是否有权访问仍由后端校验。
刷新设备会重置对话并默认选择列表中的第一台。账号无设备或加载失败时，可以继续知识问答，设备快捷操作会提示先选择设备。
聊天页面不保存访问密钥、工单或对话到浏览器存储。切换账号会清空旧对话并重新加载设备。
设备状态是数据库快照；演示数据不是实时硬件遥测。工单保存在当前配置的数据源，不会通知真实售后人员。

## 新增只读接口

- `GET /api/devices`：只返回访问密钥绑定账号拥有的设备。
- `GET /api/devices/{deviceId}/tickets?page=0&size=20`：只返回当前账号在该设备上的工单。page 从 0 开始，size 为 1–100。
- 两个接口都需要 `X-API-Key`，均不调用大模型。工单查询返回全部状态，方便后续增加工单生命周期。
- 无凭据返回 401；无权访问和不存在的设备统一返回 403；分页范围非法返回 400。
- 列表不暴露租户、用户或幂等键。设备详情和工单归属条件都由服务端身份决定。

## 验证命令

```powershell
. ./scripts/use-project-java.ps1
mvn -s maven-settings.xml '-Dtest=DeviceWorkspaceTest,ChatControllerTest,IdentityIsolationTest' test
node --test src/test/frontend/client.test.cjs
node --check src/main/resources/static/app.js
git diff --check
```

Java 测试使用独立 H2 数据库，不依赖 DeepSeek、Redis 或 PostgreSQL。
前端测试使用 Node 内置测试器，无需 npm 安装；覆盖设备提示词、密钥传递、服务失败、网络错误和不自动重试写请求。
这些测试不等于真实浏览器视觉验收；还应按上述体验步骤检查桌面与手机布局。

本轮验证：上述 12 项 Java 测试、5 项 Node 测试全部通过，JavaScript 语法检查与 diff 空白检查通过。未调用收费模型，未连接生产数据库，未执行浏览器视觉验收。
