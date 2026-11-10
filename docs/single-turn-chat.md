# 单轮只读聊天接口

POST /api/v1/chat，Content-Type: application/json。

请求：`{"message":"200克米饭多少热量？"}`。仅使用message，每次请求独立，不接入历史、用户身份或写入能力。未知食品/缺参正常追问；工具无结果不编造。没有多轮记忆，用户需要重新提交完整问题。

成功：HTTP200，`{"code":"OK","answer":"...","requestId":"..."}`。正常追问也属于成功。
错误：`{"code":"...","message":"...","requestId":"..."}`，不返回上游错误正文和堆栈。

- 400 INVALID_MESSAGE：null、空白或超过2000个Java UTF-16代码单元的消息。
- 400 INVALID_REQUEST：缺失请求体或非法JSON。
- 503 MODEL_UNAVAILABLE：默认配置没有模型。
- 504 MODEL_TIMEOUT：已识别的超时异常，包括嵌套SocketTimeoutException等。
- 502 MODEL_EMPTY_RESPONSE：模型回答为空。
- 502 MODEL_ERROR：其他模型调用异常。

ChatController检查入口，ChatService作防御校验并分类错误，SpringAiChatGateway提供只读工具。ChatGateway是可替换接口，测试使用无网络假实现。默认配置仍可无密钥启动，真实请求需要live配置。

## 运行

先按README构建并在本地设置DEEPSEEK_API_KEY，然后启动：

```sh
.tools/jdk/Contents/Home/bin/java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar backend/target/nutrilog-agent-1.0.0.jar --spring.profiles.active=live
```

```sh
curl -H 'Content-Type: application/json' -d '{"message":"200克米饭多少热量？"}' http://127.0.0.1:8080/api/v1/chat
```

## 2026-11-03验证

- 23项Java测试通过，verify打包成功；假模型覆盖非法消息零调用、追问200、嵌套超时504、空/异常回答502和未配置503。
- 默认Jar实际HTTP验证：空消息400，无模型503。
- 真实deepseek-chat HTTP样本：168935、200克返回260.00kcal、蛋白质5.38g、脂肪0.56g、碳水56.34g，并保留限定和来源；下一独立请求“200克呢？”追问食品，没有沿用前一请求。自动检查数值出现，完整表述由人工复核。
- 复现：python3 scripts/chat-http-smoke.py；真实模式加--live并配置进程密钥，会产生费用。

## 当前边界

仅监听127.0.0.1，无鉴权、无持久化、无写入、无前端、非SSE。模型单次调用30秒超时、重试0；2026-11-04已补请求级6次模型、8次工具、60秒预算，细节与取消边界见execution-budget.md。尚未强制建立候选确认状态，提示词约束不等于服务端授权。接口只返回模型文本，不声称已完成结构化营养表的前端直出。

失败测试起初受Mockito自附加JVM限制影响，改为简单假实现；默认模型缺失时获取ChatClient.Builder会失败，已改为可选ChatModel检查，启动回归通过。本次未做大规模评测或故障网络实测，不报告总体准确率。
