# NutriLog Agent

用于Java后端实习学习的饮食管理Agent，按小任务逐步实现。

## 已实现与验证

- Java21 / Spring Boot 4.0.6 / Spring AI 2.0.2项目骨架。
- 确定性热量计算工具、参数校验、JSON工具协议测试。
- 6项离线测试通过；无模型密钥可启动，HTTP健康检查返回200。
- DeepSeek真实工具调用冒烟：输入116千卡/100克、200克，工具执行1次并返回232.00，模型回答232千卡。

以上仅证明最小链路；尚未实现食品库、登录、记餐、多轮对话、执行步数保护或完整Agent。测试用116是人工提供的协议测试数值，不作为某种食物的营养事实。

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

## 食品只读演示（2026-11-03）

新增食品搜索、详情与按编号计算接口，目录暂存内存，全部为明确标记的合成教学数据。没有真实食品库或Agent业务工具注册。

- `GET /api/v1/foods?keyword=米饭`：返回生/熟两个候选，不自动猜测。
- `GET /api/v1/foods/2`：返回演示食品详情及来源标记。
- `POST /api/v1/nutrition/calculate`，JSON为 `{"foodId":2,"weightGrams":200}`。
- 返回各营养项目的amount/unit/status与source；缺失返回null/MISSING，真实零值为0/KNOWN。基础数据由服务端读取。
- 重量无效或参数缺失返回400，食品不存在返回404。

本轮累计10项离线测试通过；打包后可运行 `python3 scripts/food-http-smoke.py` 验证HTTP契约（本机18081端口，自动停止应用）。
