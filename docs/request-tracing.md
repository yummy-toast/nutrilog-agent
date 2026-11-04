# 聊天请求追踪

2026-11-04加入POST /api/v1/chat。

## 接口

服务端在Spring MVC拦截器中为每次请求生成UUID。忽略客户端传入的X-Request-Id，不作为身份、权限或幂等依据。相同消息的新请求仍生成新编号。

- 成功JSON：code、answer、requestId。
- 预期失败JSON：code、message、requestId；包含非法JSON、非法消息、模型失败/超时/执行限制。
- 响应头：X-Request-Id，与JSON的requestId一致。

编号仅表示一次请求，不替代未来写操作的operationId。

## 事件

RequestTrace保存在当前HttpServletRequest属性中，显式传入ChatService、模型网关和BudgetedChatLoop；不依赖全局可变计数器或线程本地隐式状态。

- request_started：入口生成追踪后开始。
- model_started / model_finished：模型尝试序号、耗时、OK/ERROR。
- tool_started / tool_finished：注册工具白名单名称、序号、耗时与业务状态。
- request_finished：HTTP状态、最终业务错误码、总耗时、模型与工具尝试次数。成功和失败都记录同一种结束事件，通过outcome区分。

模型计数为获准进入本轮调用阶段的尝试数，包括线程池拒绝时的失败尝试，不等于模型服务端确认收到的请求数。工具名只有注册工具名称会写日志；业务status只接受OK/NOT_FOUND/INVALID_ARGUMENT，否则标COMPLETED，异常标ERROR。

默认应用追踪日志不写完整提问、回答、工具参数、工具结果、异常正文、密钥或堆栈。模型错误另记requestId和异常类名。模型返回的自由文本不直接写入日志字段。第三方SDK的DEBUG/HTTP请求体日志未启用，不应在生产环境随意开启。

## 验证

- 35项Java测试、verify打包通过。
- 并发发送相同内容：两个不同UUID；各自两轮模型、一轮工具及开始/结束共8条追踪事件，编号一致。
- 工具NOT_FOUND记录为业务状态，不把它记为工具调用异常。
- 成功、400、503、模拟504等响应头与JSON编号一致；客户端伪造编号被忽略。
- 日志测试捕获应用根日志；人为构造的敏感输入、模型回答、工具参数/结果及异常消息标记均未出现。
- 失败日志仍能按编号找到request_finished和MODEL_ERROR。
- 打包Jar实际HTTP验证400/503及编号一致性通过。此次未额外调用付费模型，模型循环使用可控制的假实现验证。

复现默认HTTP检查：python3 scripts/chat-http-smoke.py。原有--live模式也已增加响应编号一致性检查，但本轮未重跑真实模式。

## 范围

只覆盖映射到聊天控制器的MVC请求；不承诺容器在路由前拒绝的请求也带编号。没有新增日志持久化平台、跨进程追踪、用户身份、会话记忆、指标面板或写入。日志只存在当前输出中，后续部署需配置采集和保留策略。
