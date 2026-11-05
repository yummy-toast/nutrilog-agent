# 登录与会话归属：本地第一阶段

日期2026-11-05。本阶段是Spring Security服务端登录会话和会话元数据目录，不是完整持久化账号/多轮系统。

## 身份

采用Spring Security表单登录，口令由BCrypt验证，使用服务端HttpSession与HttpOnly、SameSite=Lax的JSESSIONID。登录时框架轮换会话ID；退出使原服务端会话失效。闲置过期配置30分钟。本机HTTP未设置Secure cookie；以后部署HTTPS时需开启。

普通配置提供空用户目录，没有自动生成的默认账号；只有显式demo-auth配置才加载两位教学用户：alice / alice-demo-only，bob / bob-demo-only。它们是公开演示口令，不能用于公网。用户与会话元数据暂存内存，重启清空；没有注册、密码修改、账号恢复或登录限流。未来持久化账号使用稳定用户ID，本阶段owner使用框架确认的用户名，且不提供改名。

请求中的userId不参与身份解析。SessionController只从Principal取得当前身份。服务层只返回当前用户拥有的会话；不存在或他人会话统一403 SESSION_ACCESS_DENIED，不返回他人的标题，也不调用模型。此时没有消息正文，因此“读取消息前拒绝”的真实消息测试尚待消息模块实现。

## HTTP接口

- GET /api/v1/auth/csrf：取得当前浏览器会话的CSRF令牌与headerName，保留cookie。
- POST /api/v1/auth/login：application/x-www-form-urlencoded，参数username/password；附上CSRF头。成功200 OK，错误口令401 LOGIN_FAILED。
- GET /api/v1/auth/me：返回已验证用户名；匿名401。
- POST /api/v1/auth/logout：附CSRF，退出后原cookie不能继续访问受保护资源。
- POST /api/v1/sessions：JSON只需要title（1至100个Java字符单位），附CSRF；owner由服务端绑定。
- GET /api/v1/sessions：只列本人会话。
- GET /api/v1/sessions/{UUID}：校验归属后返回sessionId/title/createdAt。
- DELETE /api/v1/sessions/{UUID}：校验CSRF和归属后删除，返回204。

登录成功会轮换CSRF状态，应重新GET令牌；失败、退出相关令牌和cookie不得记入业务日志。缺少/错误CSRF返回403 FORBIDDEN。会话参数错误返回400 INVALID_REQUEST。安全过滤器在MVC追踪之前执行，此阶段auth/sessions错误只包含code，没有承诺requestId；聊天追踪不受影响。

既有health、foods、nutrition/calculate和stateless chat保留本机公开只读能力，其中两个只读POST不依赖身份、不写业务记录，排除CSRF检查。新sessions操作保留CSRF。没有启用Basic认证，没有跨域放行。

## 运行与复现

构建后启动demo-auth配置，不需要模型密钥：

```sh
.tools/jdk/Contents/Home/bin/java -jar backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo-auth
```

```sh
python3 scripts/session-auth-smoke.py
python3 scripts/session-auth-smoke.py --no-demo
```

第一个脚本启动本机18083，模拟两个独立cookie浏览器；第二个核查默认配置演示账号不可登录。均在结束后关闭服务，不打印口令/token/cookie，不调用模型。

## 验证

39项Java测试与verify打包通过。新增会话目录测试与真实Spring Security过滤链测试；过滤链覆盖匿名拒绝、CSRF、登录ID轮换、伪造userId无效、跨用户查看/删除被拒绝和退出失效。

独立Jar HTTP检查覆盖错误口令401、无CSRF403、正确登录、服务端绑定owner、同用户两个不同会话、Bob列表为空、Bob不能读取/删除Alice会话、Alice删除、退出后401且Bob仍登录。默认配置拒绝演示账号。既有聊天400/503及食品搜索计算HTTP回归通过。第一次真实登录暴露BCrypt编码缺少{bcrypt}标识，修正后回归通过。

没有消息历史、会话状态更新、同会话串行门禁、会话聊天入口或数据库。现有POST /chat仍是独立请求，不接受sessionId。后续先接消息存储与会话执行门禁，再把多轮上下文交给模型。

参考：https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html 与 https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html 。

## 会话快照与执行门禁

GET /api/v1/sessions/{UUID}/history 在归属检查后返回已提交的 turns 和 state。新会话的历史与候选为空，选中食品、重量和重量依据为 null。

SessionDirectory.executeTurn 在单个会话上获取执行权，将已提交快照交给执行器。执行器成功返回回答与新状态后，目录一次发布用户消息、回答和状态。异常保留原快照。历史查询可在执行期间读取上一次已提交结果。同一会话正在执行时，其他轮次或删除返回 409 SESSION_BUSY。不同会话的执行权独立。

本阶段继续使用单进程内存。提交路径通过假执行器验证，会话消息的 HTTP 写入口及多轮模型接入在下一阶段实现。41 项 Java 测试通过，覆盖失败回滚、并发门禁、归属与历史接口。
