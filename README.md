# NutriLog Agent

面向Java后端实习的饮食管理Agent，正在逐步实现。当前只有项目骨架、健康检查和营养计算工具；尚未实现食品库、记餐、登录或完整Agent。

## 当前环境

Java 21、Spring Boot 4.0.6、Spring AI 2.0.2、Maven 3.9.9。项目本地工具放在 `.tools/`，不提交Git。

Intel Mac首次执行 `bash scripts/setup-macos.sh`，将工具下载到项目目录并运行测试。当前环境下载受限，构建尚未通过验证。

```sh
export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PWD/.tools/apache-maven-3.9.9/bin:$PATH"
mvn -f backend/pom.xml test
mvn -f backend/pom.xml spring-boot:run
```

访问 `http://127.0.0.1:8080/api/v1/health`。默认不调用模型，不需要密钥。

真实模型验证单独使用live配置；`.env.example`仅用于说明变量，Spring不会自动读取.env。请在终端设置环境变量，禁止提交密钥。

## 参考

- https://github.com/leimuwife/Food_calories ：业务流程与工具调用设计参考。
- https://github.com/buxiangshuohua0724/nutrition-agent ：数据来源与缺失值设计参考。
- https://docs.spring.io/spring-ai/reference/getting-started.html ：版本兼容与集成。

当前Java代码独立编写，没有复制上述项目代码或数据。尚未选定项目分发许可证。

显式真实冒烟（需先设置密钥，会产生API费用）：

```sh
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=live,live-smoke --spring.main.web-application-type=none"
```

冒烟会检查工具确实被调用；答案正确性仍需核对，不能据此声称完整Agent通过。
