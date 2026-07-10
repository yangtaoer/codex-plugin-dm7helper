# Codex 达梦 7 数据库插件设计规格

日期：2026-07-10

状态：书面规格已确认，进入实施计划

目标插件名：`dm7-database`

## 1. 目标与成功标准

本项目交付一个可安装、可分享的 Codex 插件，使 Codex 和用户能够通过同一套受控能力访问达梦 7 数据库。

插件必须满足以下结果：

1. Codex 能通过 MCP 工具查询数据库、执行 DDL/DML、查看元数据、查询执行状态、取消执行和导出发版 SQL。
2. 用户能从 Codex 打开本地管理控制台，配置多个达梦连接并选择默认连接。
3. 控制台能手动执行 SQL，实时显示执行阶段和结果，并正确显示、复制和导出中文内容。
4. 每个新的 Codex 会话第一次调用插件时，自动建立独立的 `v001` SQL 日志文件，即使第一次调用只是查询或打开控制台。
5. 发版 SQL 仅记录成功完成的 DDL/DML；测试和 mock SQL 必须排除，其中 `seed`、`sample` 明确定义为测试/mock fixture 的子类并同样排除。
6. 发版导出后，当前会话日志被原子封存，活动日志立即以新版本重新建立，效果等同于“截断当前日志并进入下一版本”。
7. 导出的 SQL 保留原始语义，不增加幂等判断、不去重、不改写为 `IF EXISTS` 等形式。
8. 插件包不包含测试密码或其他凭据；达梦 JDBC 驱动由使用者配置，避免把专有驱动作为可分享包的一部分重新分发。

## 2. 已确认的产品边界

### 2.1 UI 承载方式

Codex 当前公开插件能力支持打包 MCP 服务，但没有公开保证“本地 STDIO MCP 返回的 UI Resource”能直接在 Codex 任务正文内渲染。真正的 Apps SDK 内嵌 UI 需要预先注册的 `plugin_asdk_app...` 标识以及可访问的 HTTP MCP 服务。

因此第一版采用本地混合模式：

- Codex 通过插件内的 STDIO MCP 服务调用数据库能力。
- 同一 Java 进程仅在 `127.0.0.1` 启动管理控制台。
- `open_console` 工具返回带短期会话令牌的本地 URL，由 Codex 的应用内浏览器打开。
- 所有核心能力均可通过 MCP 完成；控制台是检查、编辑、比较、确认和浏览的完整交互层。
- 将来若提供 Apps SDK App ID，可以复用同一服务契约增加真正内嵌 UI，而不改变数据库执行核心。

### 2.2 支持平台与运行时

- 第一版正式验证环境为 Windows 11、Codex Desktop、Java 21 和达梦 7 驱动。
- 服务编译为 Java 17 字节码；使用者需要 Java 17 或更高版本。
- 前端在构建阶段生成静态资源并打入服务 JAR，使用者不需要 Node.js。
- 启动失败时必须返回可操作的错误信息，例如 Java 版本不足、驱动路径不存在或端口无法监听。

### 2.3 驱动分发

- 可分享插件中不提交 `Dm7JdbcDriver-7.0.jar`。
- 管理页允许选择本地驱动 JAR，并持久化驱动文件路径和 SHA-256。
- 测试时由 `DM7_IT_DRIVER_JAR` 指向用户提供的 `Dm7JdbcDriver-7.0.jar`；机器上的绝对路径不写入仓库或验收报告。
- 服务显式加载 `dm7.jdbc.driver.Dm7Driver`，不能依赖 JDBC Service Provider 自动发现。

## 3. 总体架构

插件由六个清晰边界组成：

1. **Codex 插件包**：manifest、MCP 配置、技能、会话钩子、资源、启动入口和构建产物。
2. **MCP 适配层**：处理 JSON-RPC/STDIO、工具 schema、工具注解、结构化响应和 Codex 会话上下文。
3. **应用服务层**：连接配置、SQL 执行、任务取消、会话、历史、发版日志和导出。
4. **达梦 JDBC 层**：动态驱动加载、连接、Statement 生命周期、元数据与结果映射。
5. **本地 Web 控制台**：连接管理、SQL 编辑器、实时事件、结果表格、执行历史和发版页。
6. **持久化层**：`PLUGIN_DATA` 下的配置、加密凭据、SQLite 状态库、发版 SQL、导出文件和运行日志。

AI 调用和控制台操作都只能进入应用服务层，不允许控制台绕过日志、安全检查或事务规则直接操作 JDBC。

## 4. 插件目录与分发结构

仓库使用可分享的 repo marketplace 布局：

```text
<repo>/
├─ .agents/plugins/marketplace.json
├─ plugins/dm7-database/
│  ├─ .codex-plugin/plugin.json
│  ├─ .mcp.json
│  ├─ skills/dm7-database/SKILL.md
│  ├─ hooks/hooks.json
│  ├─ hooks/session-context.ps1
│  ├─ lib/dm7-codex-plugin.jar
│  ├─ assets/
│  ├─ scripts/
│  ├─ server/
│  ├─ web/
│  ├─ README.md
│  ├─ LICENSE
│  └─ THIRD_PARTY_NOTICES.md
└─ docs/
```

`.codex-plugin/plugin.json` 提供完整名称、版本、说明、能力、图标、截图和默认提示。`.mcp.json` 声明一个 STDIO 服务并通过 `java -jar` 启动插件 JAR。实现阶段必须在真实安装缓存路径中验证插件根目录变量展开与带空格/中文路径；不得使用开发机绝对路径作为最终配置。

repo marketplace 用于从 Git 仓库分享和安装。开发期另使用个人 marketplace 安装副本进行 Codex 本地验证，但个人 marketplace 文件不作为仓库产物提交。

## 5. 会话识别与生命周期

### 5.1 会话标识来源

会话解析优先级为：

1. MCP 服务进程继承的 `CODEX_THREAD_ID`。
2. 受信任的 `SessionStart` 钩子写入 `PLUGIN_DATA/session-context/` 的 `session_id` 映射，用于交叉校验和诊断。
3. 测试客户端或非 Codex 主机显式提供的会话覆盖值。
4. 进程级随机 UUID 回退；此时响应必须标记 `isolation=process_fallback`，发版操作要求显式确认。

当前 Codex Desktop 环境已验证存在 `CODEX_THREAD_ID`。插件仍保留回退逻辑，避免依赖未公开保证的环境变量时静默混用不同会话。

会话隔离不能只靠单元测试推定。完成验收必须从两个真实的新 Codex 任务分别调用已安装插件，保存脱敏后的进程 ID、线程 ID 哈希和会话目录证据，并证明两次第一次调用各自创建独立的 `v001 active.sql`。若真实主机复用同一 MCP 进程且不提供逐调用可信线程标识，则该要求判定为未完成，不能用进程 UUID 回退冒充 Codex 会话隔离。

### 5.2 首次调用

任何工具第一次在新会话中被调用时，服务在返回结果前完成以下动作：

1. 在 SQLite 中创建逻辑会话记录。
2. 建立版本 `v001`。
3. 创建 UTF-8 无 BOM 的 `active.sql`，写入只包含非敏感元数据的头部注释。
4. 记录当前连接指纹；若尚未选择连接，使用 `unbound`，首次数据库操作时补写状态库中的指纹，但不重写已存在 SQL 内容。

这个流程是幂等的会话初始化，不代表导出的 SQL 需要幂等。

### 5.3 控制台会话绑定

`open_console` 创建一个短期、单次兑换的随机令牌。浏览器兑换后得到 HttpOnly、SameSite=Strict 会话 Cookie，并绑定：

- Codex 会话 ID；
- 服务进程；
- 允许的 loopback Origin；
- 过期时间。

兑换端点成功后立即返回 `303` 跳转到不含令牌的控制台 URL。所有控制台响应设置 `Referrer-Policy: no-referrer`；访问日志和错误日志不得记录查询字符串；已兑换或过期令牌再次使用必须失败。控制台不能通过 URL 参数暴露数据库密码、JDBC 凭据或可长期复用的认证令牌。

## 6. 连接配置与凭据

### 6.1 连接模型

一个连接配置包含：

- 稳定 UUID、显示名称和是否默认；
- 驱动 JAR 路径、SHA-256、驱动类；
- 原始 JDBC URL；
- 用户名、可选 schema；
- 连接、socket、查询超时；
- 最大返回行数和结果字节数；
- 创建时间、更新时间和最近测试结果。

默认连接对 MCP 和控制台一致。删除当前默认连接前必须先选择替代项或明确进入“无默认连接”状态。

### 6.2 密码存储

- 密码只能通过本地控制台写入。
- MCP 工具不接受、不返回密码。
- `CredentialStore` 使用 AES-256-GCM；随机主密钥保存在 `PLUGIN_DATA/secrets/`，Windows 上对当前用户设置限制 ACL，其他系统使用 owner-only 文件权限。
- 配置 JSON、SQLite、服务日志、MCP 返回和导出 SQL 中不得出现密码。
- 更新连接时，空密码输入表示保留原值；清除密码必须使用单独动作。

### 6.3 达梦 URL 诊断

用户提供的驱动会接受 `jdbc:dm7://host:port/SYSTEM?...`，但其旧版解析器会忽略 `/SYSTEM` 路径段。管理页保留原始 URL，同时显示诊断和两个明确建议：

- 数据库名使用 `dbname=SYSTEM`；
- schema 使用 `schema=SYSTEM` 或在配置中单独填写。

插件不得在保存或连接时无提示修改 URL。

## 7. MCP 工具契约

所有工具返回结构化 JSON，同时提供简短模型可读文本。工具注解必须真实反映行为。

### 7.1 `dm7_open_console`

- 初始化会话并启动/复用 loopback 控制台。
- 返回短期 URL、会话、版本和当前连接摘要。
- 不包含密码。
- 该工具会启动/复用本地服务并持久化控制台会话，因此 `readOnlyHint=false`、`destructiveHint=false`。

### 7.2 `dm7_list_connections`

- 返回连接 ID、名称、默认状态、URL 脱敏摘要和最近连接测试。
- `readOnlyHint=true`。

### 7.3 `dm7_test_connection`

- 使用已保存凭据测试指定连接。
- 返回服务器/驱动版本、实际用户、当前 schema、耗时和 URL 诊断。
- `readOnlyHint=true`、`openWorldHint=true`。

### 7.4 `dm7_query`

输入包括连接 ID、SQL、绑定参数、最大行数和超时。只允许查询、元数据和 `EXPLAIN`；检测到 DDL、DML、DCL、事务控制或会话修改命令时拒绝执行。

返回列元数据、行、截断标志、耗时、任务 ID、数据库指纹和中文安全的 JSON。

### 7.5 `dm7_execute`

输入包括连接 ID、SQL 脚本、绑定参数、用途、`atomic` 和 `continueOnError`。

用途是必填枚举：

- `production_change`
- `migration`
- `test`
- `mock`
- `seed`
- `sample`

工具返回每条语句的类型、成功、提交、影响行数、是否写入发版日志、未写入原因、耗时和错误。`destructiveHint=true`；Codex 应按工具审批策略确认修改操作。

### 7.6 `dm7_describe_schema`

- 分页列出 schema、表、视图、列、索引和约束。
- JDBC metadata 不完整时使用受控的达梦目录查询回退。
- `readOnlyHint=true`。

### 7.7 `dm7_get_execution` / `dm7_cancel_execution`

- 前者查询任务和阶段事件；后者调用 `Statement.cancel()`，超时后关闭 Statement/Connection。
- 取消工具是本地状态修改，但不是数据库数据修改。

### 7.8 `dm7_get_release_log`

- 返回当前会话版本、SQL 数量、预览、已排除数量和数据库指纹。
- 不返回其他会话的日志。

### 7.9 `dm7_release_export`

- 对当前会话执行原子封存和导出。
- 返回绝对文件路径、文件名、版本、语句数、字节数、SHA-256 和新活动版本。
- 该工具会封存并轮换活动日志，使用 `readOnlyHint=false`、`destructiveHint=true`，MCP 和页面都要求明确发版确认。

## 8. SQL 解析、执行与分类

### 8.1 脚本切分

切分器必须识别：

- 单引号字符串和转义；
- 双引号标识符；
- 行注释和块注释；
- 优化器 hint；
- 括号层级；
- `CREATE PROCEDURE/FUNCTION/TRIGGER` 等包含内部分号的定义块。

不能使用简单的 `split(";")` 或仅靠正则表达式识别语句类型。

### 8.2 顶层类型

可写入发版日志的类型包括：

- DDL：`CREATE`、`ALTER`、`DROP`、`TRUNCATE`、`RENAME`、`COMMENT`；
- DML：`INSERT`、`UPDATE`、`DELETE`、`MERGE`。

`WITH` 按最终顶层操作分类。查询、DCL、事务控制、会话命令、`CALL` 和 `EXPLAIN` 不写入发版日志。

匿名 `BEGIN...END` 和无法静态确认内容的动态 SQL 在“受跟踪生产模式”中拒绝；用户可明确选择测试/样例用途以不跟踪方式执行。完整的 `CREATE PROCEDURE/FUNCTION/TRIGGER` 作为单条 DDL 保留原文。

### 8.3 事务与记录时点

- 自动提交语句：JDBC 报告成功后写入。
- 插件管理的事务：先放入待记录集合，只有 commit 成功后才追加；rollback 或 commit 失败全部丢弃。
- `atomic=true` 只允许纯 DML 脚本；脚本只要包含 DDL 或其他数据库管理提交行为，就在执行前整体拒绝，并提示改用 `atomic=false`。
- `atomic=true` 的纯 DML 中任一语句失败则回滚全部修改，不记录任何待提交 SQL。
- `continueOnError=true` 只能与 `atomic=false` 配合，每条成功语句独立判断和记录。
- `atomic=false` 的 DDL 以驱动返回成功为独立提交依据，立即判断并记录；后续语句失败不能抹掉已经由数据库提交的 DDL。执行结果明确标记 `commitBehavior=database_managed`。

数据库提交和本地日志追加无法组成跨资源原子事务。第一版不在业务数据库创建 outbox 表；极端进程崩溃可能导致“数据库已提交但 SQL 尚未追加”。执行历史保留关联 ID，页面显示未完成的日志阶段以便人工核对。

## 9. 两类记录

### 9.1 执行历史

执行历史保存在 SQLite，用于页面查询和故障诊断。它包含：

- 会话、连接指纹、调用来源、用途；
- 原始 SQL 或脚本的受限访问副本；
- 各阶段时间、语句结果、错误码、影响行数；
- 是否符合发版条件以及排除原因。

访问仅限 loopback 控制台当前用户。服务日志不重复输出完整 SQL，避免无意扩散敏感字面量。

### 9.2 发版 SQL

只有同时满足以下条件的语句进入 `active.sql`：

1. 顶层类型是允许的 DDL/DML；
2. 用途是 `production_change` 或 `migration`；
3. 数据库执行和所需提交成功；
4. 不是动态不可判定块；
5. 当前会话和连接绑定有效。

`test`、`mock`、`seed`、`sample` 无条件排除。疑似测试命名仅产生警告，不覆盖显式用途，避免文本猜测误删正式变更。

每个活动版本在第一条符合发版条件的 DDL/DML 成功时绑定一个数据库指纹。查询以及 `test`、`mock`、`seed`、`sample` 执行不改变绑定。绑定后，任何指向其他数据库指纹的正式变更都在执行前拒绝并返回 `RELEASE_LOG_CONNECTION_MISMATCH`；用户必须先发版轮换，或明确使用非发版用途。修改默认连接也不会改变已绑定版本。这样单个导出文件不会混入多个数据库的变更。

每条记录保留原 SQL、原注释和 hint，只规范文件结尾换行以及缺失的顶层分号。参数化 DML 必须展开为可重放 SQL 字面量后写入；字符串转义由达梦方言渲染器负责。无法安全渲染的参数类型拒绝进入发版日志并在结果中明确报错，不能输出占位符造成不可执行导出。

## 10. 版本与发版导出

每个会话维护独立版本，格式为 `v001`、`v002`……。

导出在会话级跨进程文件锁下执行：

1. 停止当前版本追加并 flush/fsync `active.sql`。
2. 在同一文件系统原子重命名为封存片段。
3. 从封存片段生成临时导出文件，flush/fsync 后原子重命名为最终 `.sql`。
4. 更新 SQLite 中的导出状态、序列范围和 SHA-256。
5. 版本递增，并立即创建带新头部的空 `active.sql`。
6. 释放锁；锁等待期间完成的新执行写入新版本。

封存与导出使用该版本绑定的数据库指纹。尚无正式变更的 header-only 版本保持 `unbound`。版本轮换后，新活动版本重新处于未绑定状态。

最终文件名：

```text
dm7-<session-short-id>-<version>-<yyyyMMdd-HHmmss>.sql
```

导出文件使用 UTF-8 无 BOM、LF 换行和 `application/sql; charset=utf-8`。头部只包含版本、会话短 ID、连接指纹、生成时间、语句数和源封存片段的 SHA-256，不包含密码或完整敏感 URL。最终导出文件自身的 SHA-256 仅通过工具返回和 SQLite 元数据保存，避免文件自校验值产生循环定义。

崩溃恢复会扫描“已封存但未完成导出”的片段并在页面提供恢复动作。不能使用不安全的“复制后直接 truncate”流程。

## 11. 本地控制台

### 11.1 信息架构

左侧导航：

1. 概览
2. SQL 控制台
3. 实时执行
4. 发版日志
5. 连接管理
6. 设置

顶栏始终展示当前连接、连接状态、Codex 会话短 ID、活动版本和运行任务数。

### 11.2 SQL 控制台

- SQL 编辑器支持语法高亮、行号、选中执行和全部执行。
- 控件包括连接、用途、原子事务、失败后继续、最大行数和超时。
- 查询可直接执行；检测到修改语句时必须选择用途并确认。
- 下方标签为“结果表格”“执行消息”“实时过程”。
- 结果表格支持虚拟滚动、列宽调整、排序、复制和 UTF-8 CSV/JSON 导出。
- 大字段按上限截断并提供明确标记，不允许无限占用内存。

### 11.3 实时执行

服务通过 SSE 发送单调递增事件序列：`queued`、`connecting`、`parsing`、`executing`、`committing`、`logging`、`completed`、`failed`、`cancelled`。

页面能在重连时携带 `Last-Event-ID` 补发有限历史。每个任务展示 SQL 摘要、阶段、耗时、影响行数、来源和取消按钮。

### 11.4 连接管理

- 连接卡片和编辑抽屉；支持新增、复制、测试、设为默认和删除。
- 驱动文件选择、URL 诊断、用户名、密码、schema 和高级超时。
- 密码默认隐藏，页面和网络响应永不回显原密码。
- 连接测试显示驱动版本、服务器信息、实际 schema 和中文能力探测结果。

### 11.5 发版日志

- 按会话和版本显示已记录、已排除、失败数量。
- 支持类型/用途/状态筛选、SQL 预览、执行来源和排除原因。
- “发版并导出”弹窗显示版本、语句数、目标文件名和导出后新版本。
- 成功后页面保留历史导出记录，并切换到新的空活动版本。

### 11.6 视觉规范

- 与 Codex 一致的克制型中性色界面，支持明暗主题。
- 使用系统 UI 字体和等宽 SQL 字体；信息密度适中。
- 细边框、紧凑间距、清晰层级；避免装饰性渐变、过度圆角和臃肿卡片。
- 绿色仅表示成功，琥珀色表示警告/待确认，红色表示失败/危险。
- 所有交互可键盘操作，焦点可见；文本和状态不能只靠颜色区分。
- 目标尺寸至少覆盖 1280×800 和 1440×900，无横向页面溢出。

## 12. 编码与中文保证

- JDBC 使用 `getString()` 取得 Java Unicode，禁止对结果进行手工 GBK/UTF-8 二次转码。
- JVM 进程、JSON 序列化、HTTP、SSE、CSV、JSON 下载和 SQL 文件都显式使用 UTF-8。
- HTTP 文本响应包含正确 `Content-Type` 和 `charset=utf-8`。
- STDIO MCP 只写 UTF-8 JSON-RPC 到 stdout；应用日志必须写 stderr 或文件，不能污染协议流。
- 前端以 Unicode 字符串渲染，不使用依赖系统代码页的 Blob 构造。
- 测试覆盖中文数据、中文列别名、中文注释、中文错误消息和中文导出内容的逐字符一致性。

## 13. 超时、取消与资源限制

- 默认连接超时 10 秒、socket 超时 30 秒、查询超时 60 秒，可按连接覆盖。
- 同时设置驱动属性和应用层 Future deadline。
- 取消顺序为 `Statement.cancel()`、等待短暂宽限、关闭 Statement、关闭 Connection。
- 每次查询默认最多 1,000 行，硬上限 10,000 行；结果默认 10 MiB，硬上限 50 MiB。
- 每个会话限制并发执行数量，超出进入有界队列；队列满时快速失败。
- Connection、Statement 和 ResultSet 必须在所有成功、失败、取消路径关闭。

## 14. 错误处理与安全

- 对外错误包含关联 ID、阶段、达梦错误码/SQLState 和安全消息。
- JDBC URL 在 UI 与日志中屏蔽凭据参数；密码永不进入错误上下文。
- Web 服务仅绑定 loopback，校验 Host/Origin，使用随机控制台令牌并设置严格 CSP。
- 控制台令牌只允许兑换一次；兑换后 `303` 跳转到无令牌 URL，设置 `Referrer-Policy: no-referrer`，并从所有访问/错误日志中删除查询字符串。
- 所有写操作使用预编译参数或明确的 SQL 字面量渲染，不把 UI 参数直接拼入系统目录查询。
- 发版 SQL 本身可能包含业务敏感字面量，因此目录使用当前用户限制 ACL，并在 README 中声明敏感性。
- 插件 MCP 工具使用准确的 read-only/destructive/open-world 注解；不把本地 UI 安全检查当作 Codex 工具审批的替代品。

## 15. 持久化布局

所有可写数据位于 Codex 提供的 `PLUGIN_DATA`：

```text
PLUGIN_DATA/
├─ config/connections.json
├─ secrets/master.key
├─ secrets/vault.json
├─ state/plugin.db
├─ session-context/
├─ sessions/<session-id>/active.sql
├─ sessions/<session-id>/sealed/
├─ exports/<session-id>/
└─ logs/server.log
```

插件安装目录视为只读、可替换和带版本缓存的代码目录。任何配置、数据库或日志写入插件根目录都属于缺陷。

## 16. 测试策略

### 16.1 单元测试

- SQL 切分：字符串、注释、hint、引号标识符、过程体和多语句。
- 分类：DDL、DML、CTE、查询、DCL、事务、动态块。
- 用途过滤和成功/失败/回滚记录规则。
- 首次任意工具调用创建 `v001 active.sql`。
- 会话隔离、并发追加、导出封存、版本递增和崩溃恢复。
- 活动版本首次正式变更绑定数据库指纹；切换默认连接和显式跨连接正式写入必须拒绝混写。
- `atomic=true` 拒绝所有含 DDL 的脚本；非原子脚本中已经成功提交的 DDL 不因后续失败而丢失记录。
- 参数字面量渲染和不可安全渲染类型拒绝。
- UTF-8 文件、CSV、JSON 和中文逐字符断言。
- 凭据加解密、ACL/权限诊断和敏感信息脱敏。

### 16.2 MCP 合约测试

- `initialize`、`tools/list`、`tools/call` 和错误响应。
- stdout 无非协议内容。
- 工具 schema 和安全注解。
- 模拟不同 `CODEX_THREAD_ID` 验证会话日志隔离。
- 查询工具拒绝修改 SQL，修改工具要求用途。
- 导出返回真实存在的文件、正确 SHA-256 和递增版本。

### 16.3 Web/API 测试

- 控制台令牌兑换、Cookie、Origin/Host 和 CSP。
- 令牌兑换后 `303` 到无令牌 URL、重放失败、无 Referrer 泄漏且日志无查询字符串。
- 连接 CRUD、默认连接和密码不回显。
- SQL 执行、SSE 重连、取消、历史筛选和发版导出。
- CSV/JSON/SQL 下载编码与文件名。

### 16.4 浏览器测试与视觉验收

- Playwright 覆盖主要用户路径、键盘操作和错误状态。
- 在 1280×800 与 1440×900、明暗主题截图检查。
- 检查溢出、对齐、字体、空状态、加载状态、长中文和长 SQL。

### 16.5 真实达梦 7 集成测试

使用用户提供的 URL、账号和外部密码，不把密码写入仓库或测试报告。测试流程：

测试凭据只通过以下环境变量注入：`DM7_IT_JDBC_URL`、`DM7_IT_USERNAME`、`DM7_IT_PASSWORD`、`DM7_IT_DRIVER_JAR`。可选的本地 `.env.test.local` 必须被 Git 忽略且只对当前用户可读。

1. 使用原始 URL 测试连接并记录驱动对路径段的诊断。
2. 创建随机前缀测试表。
3. 插入包含中文的行。
4. 查询并逐字符验证中文值和中文列别名。
5. 更新、删除并验证影响行数。
6. 获取表/列元数据。
7. 在 `finally` 中删除随机测试表。
8. 所有这些修改标记为 `test`，验证活动发版 SQL 仍只有头部。
9. 执行随机对象组成的 DDL+失败 DML 混合脚本，验证 `atomic=true` 在执行前拒绝，`atomic=false` 准确报告数据库已提交的 DDL，并在 `finally` 中清理对象。

真实数据库测试不能借“测试日志功能”为由把测试 SQL 标成正式变更。发版记录的正向测试由可控 JDBC 测试替身完成。

测试必须生成脱敏验收报告，至少包含：驱动 SHA-256、驱动/服务器版本、目标连接指纹、测试用例及耗时、中文逐字符断言结果、随机对象清理确认和总结果。报告不得包含密码、完整 URL 或可重放凭据。

### 16.6 插件与分发验证

- 运行插件 manifest 校验器。
- 从 repo marketplace 安装构建产物。
- 从带空格和中文的安装缓存路径启动 MCP 服务。
- 使用独立 MCP 客户端完成工具发现和调用。
- 在新 Codex 任务中确认插件能力可发现；若当前自动化环境不能创建新任务，交付前提供清晰的人工验证步骤并保留其余可自动化证据。
- 会话隔离验收不能降级为普通人工步骤：必须保留两个真实新 Codex 任务的脱敏证据，证明线程 ID 哈希不同、首次调用分别创建 `v001 active.sql`，且其中一个任务发版不会轮换另一个任务的活动版本。

## 17. 验收清单

以下项目全部成立才可宣布完成：

- 插件目录、manifest、marketplace、资产和文档通过校验。
- Codex/MCP 能发现并调用所有声明工具。
- 管理控制台具备全部六个页面和已定义交互。
- 默认连接可保存、测试和被 MCP/控制台共同使用。
- 手动 SQL、AI 查询和 AI 修改共享同一执行核心。
- 实时阶段、结果、错误、取消和历史可查看。
- 中文 JDBC 结果、页面、MCP、CSV/JSON/SQL 导出均无乱码。
- 新会话第一次调用立即创建独立 `v001 active.sql`。
- 仅成功的正式 DDL/DML 进入发版 SQL；mock/test/seed/sample 被排除。
- 单个活动版本只绑定一个数据库指纹，切换默认连接或显式连接不能造成跨库混写。
- `atomic=true` 不执行含 DDL 的脚本，非原子 DDL 的数据库管理提交不会因后续失败从记录中消失。
- 发版导出原子完成，旧版本可下载，新版本活动日志为空。
- 导出不增加幂等逻辑，不泄露密码。
- 自动测试通过，真实达梦集成测试完成并清理测试对象。
- 脱敏验收报告包含驱动/服务器、中文往返、清理和两个真实 Codex 会话隔离证据。
- 安装、分享、配置、运行、故障排查和驱动授权边界均有文档。

## 18. 已知限制

- 第一版不建立数据库侧 transactional outbox，无法绝对消除数据库提交与本地日志追加之间的极端崩溃窗口。
- 第一版不把未注册的本地 MCP UI 当作 Codex 原生内嵌页面；控制台在 Codex 应用内浏览器中打开。
- 达梦 7 驱动实现接近 JDBC 3，较新的 JDBC API 可能不支持；实现只使用已验证能力并为元数据提供回退。
- 第一版不执行或记录无法静态判定的匿名动态 SQL 作为正式发版变更。
- 插件分享包不包含达梦驱动或数据库凭据；接收方必须自行提供合法驱动和连接信息。

## 19. 参考依据

- Codex 插件结构与 marketplace：<https://learn.chatgpt.com/docs/build-plugins>
- Codex MCP：<https://learn.chatgpt.com/docs/extend/mcp?surface=cli>
- MCP-backed App 与 UI 边界：<https://learn.chatgpt.com/docs/build-app>
- Apps SDK MCP/UI：<https://developers.openai.com/apps-sdk/build/mcp-server>
