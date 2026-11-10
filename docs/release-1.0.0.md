# NutriLog Agent 1.0.0 验证指南

## 离线回归与保留场景

在项目根目录用 JDK 21 构建。评测使用 Python 3 标准库，默认启动独立 JVM、动态端口及临时 H2 数据库。每组结束后关闭应用并清理临时数据。

```sh
./backend/mvnw -B -f backend/pom.xml verify
python3 -m unittest discover -s scripts -p test_usda_import.py
python3 scripts/evaluate.py backend/target/nutrilog-agent-1.0.0.jar --suite dev
python3 scripts/evaluate.py backend/target/nutrilog-agent-1.0.0.jar --suite holdout
```

| 集合 | 场景 | 通过条件 |
| --- | --- | --- |
| 开发 | 食品查询与计算 | 米饭搜索区分生熟，熟米饭 200 克为 260 千卡，保留来源编号 |
| 开发 | 登录与未配置模型 | 匿名会话查询返回 401，缺少 CSRF 的会话写入返回 403，模型未启用返回 503 |
| 保留 | 小数重量、恢复和隔离 | 生大米 37.5 克为 136.88 千卡，待确认不计入日报，其他账号无法查看或确认，同编号重试返回原结果 |
| 保留 | 同版本的两次纠正 | 第一次确认增加版本，第二次返回 409，日报仍只有一条新增记录，备注更新后营养快照保持原值 |

保留组在业务实现完成后单独编写，预期写在评测脚本中。它衡量这组固定的 HTTP 契约，独立的盲测集和真实模型评测尚待进行。后续调试如使用了这些场景，应将它们归入开发回归。

GitHub Actions 运行 Java 测试、USDA 导入测试，并对 JAR 和 Compose 分别运行两组离线评测。手动对专用容器运行时可传入 `--url http://127.0.0.1:8080 --suite all`，其中保留组会写入演示记录。

## 浏览器演示

1. 用 `docker compose up --build -d` 启动，打开 [本地页面](http://127.0.0.1:8080/)，使用 `alice` / `alice-demo-only` 登录。
2. 若要发送对话消息，设置 `DEEPSEEK_API_KEY` 并以 `NUTRILOG_PROFILES=demo-auth,local-db,live docker compose up -d` 重建应用容器。
3. 创建会话，先问「一碗米饭有多少热量？」。检查回答是否要求确认具体食品和克数。
4. 搜索并选中熟米饭，填写 200 克并发送消息。检查答案是否与食品计算结果一致。生成午餐预览，检查来源和 260 千卡，再确认。日报应出现一条新记录。
5. 修改重量为 125 克并确认。检查原记录更新为 162.50 千卡。切换到 `bob` / `bob-demo-only`，检查其同日记录与 Alice 隔离。
6. 运行 `docker compose restart app`，重新登录 Alice，检查日报中的记录仍在。演示完成后用 `docker compose down` 停止。

演示账号和 H2 适用于本机学习。Compose 发布端口绑定在 `127.0.0.1`。默认食品目录为 3 条 USDA 样本。

## 本地验收记录

- Chrome 使用本地模型桩，通过确认超时与响应丢失恢复、纠正冲突、换食材及跨账号隔离。重启 JAR 后重新登录，日报仍读取到版本 4 的鸡蛋记录和 143 千卡。
- 真实 DeepSeek 使用 `bash scripts/live-smoke.sh food-agent-smoke` 验证两条独立路径。已选熟米饭 200 克调用计算工具并得到 260 千卡，食品有歧义时只调用搜索工具并追问。
- Compose 运行和浏览器直连真实模型仍待验证。

## 发布核对

- 确认 GitHub Actions 全部通过，记录真实模型和浏览器演示的观察结果。
- 用户完成演示和理解验收后，在通过验证的提交上创建 `v1.0.0` 标签和 GitHub Release。
