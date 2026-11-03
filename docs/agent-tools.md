# 食品Agent工具：第一步

## 职责

FoodAgentTools接收模型参数、调用FoodService并返回结构化结果，不复制计算公式。工具只有searchFoods和calculateFoodNutrition，不暴露基础热量输入，不提供写入能力。

Result包含status/data/message：OK表示业务成功；NOT_FOUND和INVALID_ARGUMENT用于已知业务错误；未知异常不伪装成成功。空候选为OK和空列表，不编造数据。

当前仅在显式food-agent-smoke配置下提供固定场景的模型验证；尚无对话HTTP入口、会话身份上下文、多轮状态更新、写入或完整执行保护。提示词引导澄清，不等同于服务端已经实现了“用户确认状态”的强制门禁。

## 2026-11-03验证

17项Java离线测试通过，verify打包成功。真实deepseek-chat执行两条独立单轮样本，temperature=0、自动重试0、单次请求30秒超时。实际调用次数按工具执行记录核查：

- selected：可信测试上下文指定168935、200克，仅执行calculateFoodNutrition一次。校验编号、重量、结果260；人工核对最终答案为热量260.00kcal、蛋白质5.38g、脂肪0.56g、碳水56.34g，并保留USDA来源、生熟和加盐限定。
- ambiguous：无选定食品，输入“200克米饭多少热量”，仅执行searchFoods一次。返回生米169756和熟米饭168935候选；人工核对回答追问生熟，没有计算本餐总量。

两条冒烟不代表整体准确率，也不代表多轮澄清完成。测试包装ObservedTools仅记录调用并委托实际工具，自动检查工具路径；自然语言完整性仍需人工核查。

打包并本地配置DEEPSEEK_API_KEY后可运行：

```sh
bash scripts/live-smoke.sh food-agent-smoke
```

真实请求有费用；不要提交密钥。这个命令使用固定测试输入，不是交互聊天。

## 阅读顺序

先看FoodAgentTools如何委托FoodService，再看FoodAgentSmokeRunner如何记录工具路径。工具调用由模型提出，参数合法性由服务端检查；后续再加入可信会话上下文和澄清状态约束。
