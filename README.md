# NutriLog Agent

用于Java后端实习学习的饮食管理Agent，按小任务逐步实现。

当前进度：只读食品与单轮Agent、执行预算、请求追踪，以及本地演示登录/会话归属。数据库和多轮消息尚未实现；以下按日期保留阶段记录。

## 已实现与验证

- Java21 / Spring Boot 4.0.6 / Spring AI 2.0.2项目骨架。
- 确定性热量计算工具、参数校验、JSON工具协议测试。
- 6项离线测试通过；无模型密钥可启动，HTTP健康检查返回200。
- DeepSeek真实工具调用冒烟：输入116千卡/100克、200克，工具执行1次并返回232.00，模型回答232千卡。

以上仅证明最小链路；已有3条USDA文件目录；尚未实现数据库、登录、记餐、多轮对话、执行步数保护或完整Agent。测试用116是人工提供的协议测试数值，不作为某种食物的营养事实。

## 本地启动

需要JDK21及Maven3.9.9。Intel Mac可运行 `bash scripts/setup-macos.sh` 在 `.tools/` 内下载独立环境并运行测试，不替换系统Java。首次下载可能较慢。

在项目根目录：

```sh
export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PWD/.tools/apache-maven-3.9.9/bin:$PATH"
mvn -Dmaven.repo.local="$PWD/.tools/m2" -f backend/pom.xml verify
java -jar backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar
```

访问 `http://127.0.0.1:8080/api/v1/health`，默认仅监听本机且不调用模型。

同时提供官方Maven Wrapper（Maven3.9.9）：在JDK21可用时可用 `./backend/mvnw -f backend/pom.xml verify` 替代本机Maven命令；Wrapper会额外下载Maven发行包。本次构建使用项目本地Maven，未单独执行Wrapper下载。

## 真实模型冒烟

先完成打包，在本地终端设置 `DEEPSEEK_API_KEY` 后运行：

```sh
bash scripts/live-smoke.sh
```

脚本不会读取.env；示例配置只说明变量名。密钥不得提交Git或发送到聊天。真实请求会产生费用。本次验证模型为deepseek-chat、temperature=0、单次超时30秒、自动重试关闭；每次冒烟预期一次工具执行。尚未实现生产Agent的全局步数/总耗时限制。

## 文档

- [第一天讲解](docs/day01.md)
- [实际验证记录](docs/verification-day01.md)
- [实施与学习进度](docs/progress.md)

## 参考与复用说明

- https://github.com/leimuwife/Food_calories ：业务流程与工具调用设计参考。
- https://github.com/buxiangshuohua0724/nutrition-agent ：来源与缺失值设计参考。
- https://docs.spring.io/spring-ai/reference/getting-started.html ：兼容版本与集成。

当前Java业务代码独立编写，未复制参考项目代码或数据。Maven Wrapper由Apache官方插件生成，保留原许可证声明；项目整体尚未选定分发许可证。

## 当前食品目录（2026-11-03）

食品服务默认加载`data/usda/selected-foods.json`中的3条真实USDA样本，Maven打包时将文件纳入Jar。启动与查询不调用外部服务；这是文件目录，尚未实现数据库。

- `GET /api/v1/foods?keyword=米饭`：返回生/熟两个候选，保留加盐、未强化等限定。
- `GET /api/v1/foods/168935`：查看选中条目与原始描述。
- `POST /api/v1/nutrition/calculate`，JSON为 `{"foodId":168935,"weightGrams":200}`，热量260.00千卡。
- 结果包含原始食品编号、英文描述、来源链接、版本、归档校验值。食品id当前等于FDC编号；旧演示编号1/2/3已撤下，无历史记录迁移需求。
- 各项目独立返回amount/unit/status；缺失为null/MISSING，已知零值为0/KNOWN。
- 食品目录缺失或损坏时启动失败，不静默回退到教学数据。

本轮累计14项Java测试通过，打包Jar的HTTP验证通过。运行 `python3 scripts/food-http-smoke.py` 可复核。本轮未重跑付费模型；116×200→232仅为原有工具协议测试，不能当作当前熟米饭数据。

目录读取通过FoodCatalog接口提供，FoodService只负责搜索与计算；后续数据库实现替换该接口，Agent工具和HTTP入口将继续复用同一服务。

## 食品Agent工具（2026-11-03）

已新增只读搜索和计算工具，共用FoodService。17项Java测试及两条真实模型路径验证通过：选定编号直接计算、歧义名称搜索后追问。尚无交互聊天接口或多轮状态管理。

复现：`bash scripts/live-smoke.sh food-agent-smoke`（需先构建并配置本地密钥，有API费用）。详见[验证和职责说明](docs/agent-tools.md)。

## 单轮聊天接口（2026-11-03）

新增 `POST /api/v1/chat`，JSON请求 `{"message":"200克米饭多少热量？"}`。每次独立、只读、不保存历史或饮食记录。默认未配置模型返回503，live配置可真实查询。23项Java测试与两条真实HTTP样本通过。详见[接口、运行与边界](docs/single-turn-chat.md)。

## 执行预算（2026-11-04）

聊天HTTP接口已加入每请求6次模型、8次工具、60秒预算；每轮SDK等待最多30秒且不超过剩余时间。33项测试及真实HTTP回归通过。超时发出取消请求，迟到响应不再执行工具；不能保证远端立即停止。容量4的模型线程池无排队，未退出任务继续占用槽位。当前本地食品工具执行前后检查预算，不作强制抢占。详见[执行限制与验证边界](docs/execution-budget.md)。

## 请求追踪（2026-11-04）

聊天成功及预期失败响应新增requestId，响应头X-Request-Id与之相同。各请求独立生成编号，日志串起模型/工具次数、耗时和结果状态，默认不写完整业务内容或凭据。35项测试与打包后的400/503 HTTP验证通过；详见[追踪设计与边界](docs/request-tracing.md)。

## 身份与会话归属（2026-11-05）

Spring Security服务端登录与内存会话目录已接入，只有demo-auth配置启用教学用户。登录/退出及会话写操作需要CSRF令牌；会话owner从已验证Principal获取，客户端userId无效。可创建、列出、查看和删除本人会话；没有消息历史或多轮聊天。

39项Java测试及真实HTTP双用户隔离、退出失效、默认账号禁用检查通过。运行方式和公开演示口令见[身份与会话说明](docs/session-ownership.md)。此阶段仅供本机演示，不是持久化账号系统。

## 记餐预览与确认（2026-11-06）

使用 demo-auth,local-db 配置启动。登录后，带 Cookie 和 CSRF 头提交 `POST /api/v1/meals/previews`，请求示例为 `{"foodId":168935,"weightGrams":200,"date":"2026-11-06","mealType":"LUNCH"}`。餐次支持 BREAKFAST、LUNCH、DINNER、SNACK，日期按 Asia/Shanghai 判断，不接受未来日期。

预览返回 PENDING、operationId、有效期及服务端计算的营养快照，有效期为十分钟。用户确认时提交 `POST /api/v1/meals/previews/{operationId}/confirm`。确认在数据库事务中把同一行转为 CONFIRMED，此时才有 recordId。重复确认返回第一次的结果，包括原确认时间与快照。待确认预览过期后返回 410 PREVIEW_EXPIRED。他人和不存在的编号均返回 404。可通过 `GET /api/v1/meals/previews/{operationId}` 核对状态。

记餐使用本地 H2 文件库。每日统计和记录纠正见下方。46 项 Java 测试通过，覆盖并发幂等、预览过期、跨用户隔离和文件库重开。打包应用的实际 HTTP 检查覆盖登录、CSRF、预览、确认及重启后重复确认。

## 每日记餐查询（2026-11-06）

使用 local-db 配置。登录后调用 `GET /api/v1/meals/daily?date=2026-11-06`。日期为实际用餐日期，使用 YYYY-MM-DD。响应返回本人当天已确认的 records，以及 totals 中的热量、蛋白质、脂肪、碳水。

各营养项单独标记完整性。COMPLETE 提供完整 amount，PARTIAL 只提供 knownSubtotal，MISSING 表示该项全部缺失，NO_RECORDS 表示当天没有已确认记录。knownCount 和 missingCount 给出覆盖情况。求和使用每条记录已经保存的两位小数快照，已知零计为零。当前由数据库筛选本人已确认的记录，应用按用餐日期筛选快照。

47 项 Java 测试及实际 HTTP 检查通过，覆盖日期、重复确认去重、待确认预览排除、跨用户隔离和营养缺项。

## 已确认记餐的纠正（2026-11-06）

从每日查询读取当前 recordId 和 version。向 `POST /api/v1/meals/records/{recordId}/correction-previews` 提交 expectedVersion 和要修改的字段，例如 `{"expectedVersion":1,"weightGrams":100}`。可修改 foodId、weightGrams、date、mealType、note。更换食品须提供新重量，空字符串可清除备注。

调用同一个确认接口确认纠正预览。数据库事务锁定预览，检查记录版本并更新原记录，再将预览标为已确认。旧版本返回 409 RECORD_CONFLICT，需要刷新并重新预览。重复确认返回该操作首次结果，每日查询返回记录的最新版本。纠正操作本身不会计入日报。

新预览保存每百克营养数据。同食品修改重量使用原数据，更换食品使用新条目，只改备注、日期或餐次保留计算结果。启动时给已有本地数据库补列。旧快照缺少每百克原值时，改变原食品重量返回 422 NUTRITION_SNAPSHOT_UNAVAILABLE，仍可修改元数据，或选择另一食品并确认新的营养快照。

49 项 Java 测试通过，包含旧库升级、快照重算、旧版本冲突和重试。打包应用通过纠正预览、确认及日报只计一条记录的 HTTP 检查。

## 从对话生成记餐预览

使用 `local-db`。用户在会话消息中通过 `selectedFoodId` 和 `weightGrams` 明确选择食品和重量。带登录 Cookie 和 CSRF 头调用 `POST /api/v1/meals/previews/from-session`，请求体只需 `sessionId`、`date` 和 `mealType`。

服务端校验会话归属，从已提交的会话快照读取食品和用户确认重量，再复用记餐服务计算营养。未确认食品或重量时返回 400，访问他人会话返回 403。模型回答中的文字不会成为记餐输入。

接口生成独立的待确认预览。后续聊天改变食品或重量时，原预览保留生成时的营养快照。核对后调用 `POST /api/v1/meals/previews/{operationId}/confirm`，此时记录才进入日报。同一次确认重试复用原 operationId。

## 浏览器演示

构建后，从项目根目录启动本地服务：

```sh
java -jar backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo-auth,local-db
```

打开 [本地演示页](http://127.0.0.1:8080/)，使用演示账户 `alice` / `alice-demo-only`。登录、创建会话、搜索食品和查询日报可以离线体验。要发送对话消息，先在本地环境设置 `DEEPSEEK_API_KEY`，再追加 `live` 配置重启（`demo-auth,local-db,live`）。

创建会话后搜索「米饭」，选择熟米饭，填写 200 克并发送一条消息。生成午餐预览，核对营养后点击「确认记餐」，页面会刷新对应日期的日报。页面随 JAR 提供，浏览器复用服务端 Cookie 和 CSRF，无需单独启动前端。

在日报中点击「修改」可调整同一条记录的重量、日期、餐次或备注。核对修改预览后点击「确认修改」。如果其他页面已经更新该记录，本页会刷新原日期的日报，并要求从最新版本重新预览。

修改记录时也可以搜索并选择替换食品。选择后需要重新填写重量，即使克数与原记录相同。预览显示新食品的营养和数据来源，确认后更新原记录。

## 确认恢复与记餐审计

浏览器确认超时、断线或收到服务端 5xx 后，按同一 `operationId` 查询预览状态。已确认则刷新日报，仍待确认或暂时无法核对时可在当前页面重试同一个操作。明确过期时需重新生成预览。页面刷新后可以查询日报核对已落库的记录，预览操作编号保存在当前页面内存中。

H2 的 `meal_audit` 表保存每次成功确认的操作编号、账号、记录编号、事件类型、版本及发生时间（Unix 毫秒）。审计与业务更新使用同一事务，幂等重试及失败确认不会新增事件。审计从升级后首次成功确认开始，供本地数据库排查使用。
