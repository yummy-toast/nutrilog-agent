# 第一天实际验证记录

日期：2026-11-02；环境：Intel macOS。

## 环境

- 项目独立Corretto JDK21.0.13，Maven3.9.9；系统Java未替换。
- Spring Boot4.0.6，Spring AI2.0.2。
- JDK、缓存、原始构建和模型日志位于被Git忽略的.tools。
- Docker尚未安装，本阶段不依赖数据库或Docker。

## 验证结果

1. `mvn verify`通过，6项测试，0失败/0错误/0跳过。
2. 计算测试：按重量换算、零值与缺失区分、舍入、非法参数拒绝。
3. Spring AI工具协议：JSON参数映射并执行Java方法。
4. 无模型密钥启动通过。
5. 实际启动Jar，本机18080端口健康接口返回HTTP200及status=UP；检查结束后服务已停止。
6. DeepSeek真实冒烟执行两次。第一次工具调用成功，但控制台中文编码异常；修正启动编码并补强入参/答案检查后再次通过。

最终真实执行证据：

```text
LIVE_SMOKE inputs=116,200
LIVE_SMOKE toolResult=232.00
LIVE_SMOKE toolCalls=1
LIVE_SMOKE answer=计算结果：该食物每100克116千卡，吃了200克，总热量为 **232千卡**。
```

配置：deepseek-chat，temperature=0；单次请求超时30秒、自动重试0。一次工具执行通常涉及工具选择和最终回答两个模型请求；当前未采集准确请求数、token和账单，不能报告准确成本。

## 修复与限制

- 修正ToolCallbacks导入包名为org.springframework.ai.support。
- 默认关闭不使用的模型功能，避免无密钥开发启动失败。
- 控制台UTF8输出在冒烟脚本中显式设置。
- Maven Wrapper已生成并通过shell语法检查，但未额外运行其发行包下载；构建使用同版本本地Maven。
- 尚无食品数据源、认证、记餐写入、多轮评测和生产执行限制；不能把这些测试当成完整Agent验收。
- 用户理解验收待继续，功能通过与理解通过分开记录。
