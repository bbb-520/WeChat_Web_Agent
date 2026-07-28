# summer-bootstrap — 帮助文档

> 模块定位：**启动与装配**。持有 `@SpringBootApplication` 入口、组件扫描与 Mapper 扫描，集中管理运行时配置（`application.yml` + `config/*.yml`）。本身不含业务逻辑，是把其余三个模块「拼装成可运行应用」的胶水层。

## 1. 职责

- 应用入口：`SummerApplication.main()`
- 组件扫描：覆盖 `log.summer.common` / `log.summer.aigc` / `log.summer.bot`
- Mapper 扫描：`log.summer.aigc.mapper`
- 运行时配置聚合：`spring.config.import` 引入 `security.yml` / `ai.yml` / `bot.yml`
- 打包为可执行 Spring Boot 应用（唯一带 `spring-boot-maven-plugin` 的模块）

## 2. 依赖

- **依赖**：`summer-bot`、`summer-aigc`、`summer-common`、`spring-boot-starter-actuator`
- **被依赖**：无（顶层模块）
- 依赖链在此终结：`bootstrap → bot → aigc → common`。

## 3. 包结构

```
summer-bootstrap/
├── pom.xml
└── src/main/
    ├── java/log/summer/bootstrap/
    │   └── SummerApplication.java
    └── resources/
        ├── application.yml          # 主配置
        └── config/
            ├── ai.yml                # AI 模型配置（被 aigc 使用）
            ├── bot.yml               # 机器人业务配置（被 aigc 使用）
            └── security.yml          # API Key（gitignored，需自行创建）
```

## 4. 关键文件说明

| 文件 | 说明 |
|------|------|
| `SummerApplication.java` | `@SpringBootApplication` + `@EnableScheduling` + `@MapperScan("log.summer.aigc.mapper")` + `@ComponentScan`（common/aigc/bot）。`main` 直接 `SpringApplication.run`。 |
| `application.yml` | 端口 8080；`spring.config.import` 引入三个 config；`datasource`（MySQL `wxbot_db`）；`mybatis-plus`；`management.endpoints` 暴露 `health,metrics`；`flyway.enabled=true`，`locations=classpath:db/migration`。 |
| `config/ai.yml` | DashScope 模型绑定：chat=qwen-plus、image=wan2.5-t2i-preview、voice.tts=cosyvoice-v1、voice.asr=paraformer-v2，超时 300s。 |
| `config/bot.yml` | `bot.cache`（待编辑图 TTL/上限）、`bot.file.max-size-mb=20`、`bot.weather.base-url`、图片编辑系统提示词。 |
| `config/security.yml` | 仅放 `spring.ai.dashscope.api-key`，**默认不存在，需自行创建**且已被 gitignore。 |

> 注：`ai.yml` / `bot.yml` 内部分注释沿用了旧类名（`IntentClassifier` 等），仅为注释，不影响运行。

## 5. 构建与运行

```bash
# 安装全部模块（首次或 common/aigc/bot 有改动时）
mvn clean install

# 运行
mvn -pl summer-bootstrap spring-boot:run

# 或打包后运行
mvn -pl summer-bootstrap package
java -jar summer-bootstrap/target/summer-bootstrap-0.0.1-SNAPSHOT.jar
```

启动后微信扫码登录即可对话；健康检查：`http://localhost:8080/actuator/health`，指标：`/actuator/metrics`。

## 6. 配置生效顺序

`application.yml` 中 `spring.config.import`：
1. `optional:classpath:config/security.yml` —— 可选，缺失不报错
2. `classpath:config/ai.yml` —— 必选
3. `classpath:config/bot.yml` —— 必选

`ai.yml` / `bot.yml` 位于本模块 resources，但因依赖传递，`summer-aigc` 运行时也能读到 classpath 资源（提示词 `classpath:prompts/*.txt` 来自 `summer-common`）。

## 7. 扩展指引

- **改端口 / 数据源**：改 `application.yml`。
- **加新配置命名空间**：在 `config/` 下加 yml，并在 `application.yml` 的 `spring.config.import` 追加；用 `@ConfigurationProperties` 在 `summer-aigc` 中绑定。
- **加新传输模块**：在父 `pom.xml` 的 `<modules>` 与 `summer-bootstrap` 的 `<dependencies>` 中引入，并确保 `SummerApplication` 的 `@ComponentScan` 覆盖其包。
- **加新 actuator 端点**：`management.endpoints.web.exposure.include` 追加。

## 8. 相关文档

- [上层 README](../README.md)
- [summer-bot/HELP.md](../summer-bot/HELP.md)
- [summer-aigc/HELP.md](../summer-aigc/HELP.md)
