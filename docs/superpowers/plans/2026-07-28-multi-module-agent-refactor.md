# Multi-Module Agent Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the single-module WeChat bot into 4 Maven modules (common, aigc, bot, bootstrap) and replace the one-shot IntentClassifier+AgentRouter dispatch with a dual-loop ReAct agent engine.

**Architecture:** `bootstrap → bot → aigc → common` dependency chain. `summer-common` is a zero-dependency toolkit. `summer-aigc` defines port interfaces (`BotInboundPort`, `MessageSender`) and owns the `AgentLoop` orchestrator, all `@Tool` implementations, entities, and RAG. `summer-bot` implements the ports with ILink. `summer-bootstrap` wires everything and owns `@SpringBootApplication`.

**Tech Stack:** Spring Boot 3.2.10, Java 21, Spring AI Alibaba DashScope, MyBatis-Plus, Flyway, WeChat ILink SDK 2.3.3, Lombok 1.18.46

## Global Constraints

- Java 21, Spring Boot 3.2.10 parent POM
- Lombok 1.18.46 (explicit version in annotation processor paths)
- Dependency chain: `bootstrap → bot → aigc → common` (no cycles)
- Every `@Tool` method MUST return `ActResult` with `success` + `errorMessage` fields
- `AgentLoop` owns the while-iteration and ChatMemory; Spring AI is a stateless single-call utility
- Slash commands (`/help`, `/status`, `/cancel`, unknown `/xxx`) are pre-intercepted before `AgentLoop`
- Chat/RAG is the fallback when LLM returns no tool calls
- Flyway replaces `schema.sql` always-init mode
- `ArgumentResolver` placeholder syntax: `${message.image}`, `${message.file}`, `${message.fileName}`

---

## File Structure

### summer-common (zero Spring dependencies)
```
summer-common/pom.xml
summer-common/src/main/java/log/summer/common/
  exception/
    BotException.java              ← moved from linkDemo.exception
    AIServiceException.java        ← moved
    ConfigurationException.java    ← moved
    FileRecognitionException.java  ← moved
    ImageGenerationException.java  ← moved
    VoiceSynthesisException.java   ← moved
    // NOTE: GlobalExceptionHandler is NOT here — it depends on MessageSender (aigc port),
    // so it lives in summer-aigc/config/
  enums/
    Timbre.java                    ← moved from linkDemo.enums
    RouteContext.java              ← moved from linkDemo.enums
  constant/
    Prompts.java                   ← constants for prompt template keys
summer-common/src/main/resources/prompts/
  system.txt                       ← moved from linkDemo resources
  file-system.txt                  ← moved from linkDemo resources
```

### summer-aigc (agent core)
```
summer-aigc/pom.xml
summer-aigc/src/main/java/log/summer/aigc/
  port/
    BotInboundPort.java            ← NEW: void onMessage(BotMessage msg)
    BotMessage.java                ← NEW: record(userId, text, imageBytes, fileBytes, fileName, context)
    MessageSender.java             ← moved from linkDemo.service, unchanged
  loop/
    ThinkResult.java               ← NEW: record(finalAnswer, toolCalls)
    ActResult.java                  ← NEW: record(success, data, errorMessage) + factory methods
    ArgumentResolver.java           ← NEW: resolves ${message.image} etc.
    AgentLoop.java                  ← NEW: orchestrator, while-loop, ChatMemory management
  tool/
    ToolRegistry.java              ← NEW: scans @Tool beans, generates List<FunctionCallback>
    weather/WeatherTool.java       ← converted from WeatherAgent
    image/ImageGenTool.java        ← converted from ImageGenAgent
    image/ImageRecognitionTool.java ← converted from ImageRecognitionAgent (recognize + edit)
    voice/TtsTool.java             ← converted from VoiceGenAgent (synthesize + switch)
    file/FileTool.java             ← converted from FileAgent
    idiom/IdiomGameTool.java       ← converted from IdiomGameService
    reminder/ReminderTool.java     ← converted from ReminderTools
    navigation/NavigationTool.java ← converted from NavigationTools
    memory/MemoryStatusTool.java   ← converted from MemoryMonitorTools
    ImageCacheManager.java         ← moved from tools.image
  service/
    ChatService.java               ← moved + adapted: wraps ChatClient calls for AgentLoop
    ChatPersistenceService.java    ← moved from linkDemo.service
    IConversationService.java      ← moved from linkDemo.service
    IMessageService.java           ← moved from linkDemo.service
    IUserMemoryService.java        ← moved from linkDemo.service
    IImageRecordService.java       ← moved from linkDemo.service
    IFileRecordService.java        ← moved from linkDemo.service
    IIdiomGameRecordService.java   ← moved from linkDemo.service
    IWeatherQueryService.java      ← moved from linkDemo.service
    ITimbreChangeService.java      ← moved from linkDemo.service
    IDocumentChunkService.java     ← moved from linkDemo.service
    impl/*ServiceImpl.java         ← all moved from linkDemo.service.impl
  entity/
    Conversation.java              ← moved
    Message.java                   ← moved
    UserMemory.java                ← moved
    ImageRecord.java               ← moved
    FileRecord.java                ← moved
    IdiomGameRecord.java           ← moved
    WeatherQuery.java              ← moved
    TimbreChange.java              ← moved
    DocumentChunk.java             ← moved
    VoiceResult.java               ← moved
  mapper/
    *Mapper.java                   ← all moved from linkDemo.mapper
  rag/
    DocumentChunkingService.java   ← moved
    EmbeddingService.java          ← moved
    RAGContextAugmenter.java       ← moved
    RAGRetrievalService.java       ← moved
    VectorStoreService.java        ← moved
  config/
    AiConfig.java                  ← moved + adapted: ChatClient beans, ChatMemory
    GlobalExceptionHandler.java    ← moved from linkDemo.exception (depends on MessageSender port)
    BotProperties.java             ← moved from linkDemo.config
    VoiceProperties.java           ← moved from linkDemo.config
    HttpClientConfig.java          ← moved from linkDemo.config
    TimbreSession.java             ← moved from tools.voice, session-scoped state
    BotMetrics.java                ← moved from tools
  config/ai.yml                    ← moved from resources/config
  config/bot.yml                   ← moved from resources/config
  db/migration/V1__initial_schema.sql  ← NEW: Flyway baseline (contents of schema.sql)
```

### summer-bot (transport adapters)
```
summer-bot/pom.xml
summer-bot/src/main/java/log/summer/bot/
  ILinkBotAdapter.java             ← NEW: implements BotInboundPort, wraps ILinkClient
  SlashInterceptor.java            ← NEW: /help, /status, /cancel, unknown handler
  RetrySender.java                 ← NEW: extracted sendWithRetry logic
```

### summer-bootstrap (launcher)
```
summer-bootstrap/pom.xml
summer-bootstrap/src/main/java/log/summer/bootstrap/
  SummerApplication.java           ← @SpringBootApplication, @MapperScan, @ComponentScan
  AssemblyConfig.java              ← additional bean wiring if needed
summer-bootstrap/src/main/resources/
  application.yml                  ← composited from current application.yml (minus ai/bot sub-configs)
  config/security.yml              ← optional, gitignored
```

### summer-dev (parent POM)
```
pom.xml                            ← REWRITE: <packaging>pom, <modules>, <dependencyManagement>
```

---

## Task Decomposition

### Task 1: Parent POM

**Files:**
- Modify: `pom.xml` (rewrite)

**Interfaces:**
- Produces: Maven parent POM with `<packaging>pom`, `<modules>` listing common/aigc/bot/bootstrap, `<dependencyManagement>` with all dependency versions, Spring Boot parent, Java 21, Lombok 1.18.46

- [ ] **Step 1: Rewrite pom.xml as multi-module parent POM**

Rewrite the existing `pom.xml` to be a parent POM. Move all `<dependencies>` into `<dependencyManagement>` (not `<dependencies>` — the parent should only manage versions, not inherit them). Add `<modules>` block declaring `summer-common`, `summer-aigc`, `summer-bot`, `summer-bootstrap`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.10</version>
        <relativePath/>
    </parent>

    <groupId>log.summer</groupId>
    <artifactId>summer-dev</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>summer-dev</name>

    <modules>
        <module>summer-common</module>
        <module>summer-aigc</module>
        <module>summer-bot</module>
        <module>summer-bootstrap</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <lombok.version>1.18.46</lombok.version>
        <spring-ai-alibaba.version>1.0.0.1</spring-ai-alibaba.version>
        <dashscope-sdk.version>2.16.4</dashscope-sdk.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <tika.version>2.9.4</tika.version>
        <ilink-sdk.version>2.3.3</ilink-sdk.version>
        <jsonschema-generator.version>4.37.0</jsonschema-generator.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Spring AI Alibaba DashScope -->
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
                <version>${spring-ai-alibaba.version}</version>
            </dependency>
            <!-- DashScope SDK -->
            <dependency>
                <groupId>com.alibaba</groupId>
                <artifactId>dashscope-sdk-java</artifactId>
                <version>${dashscope-sdk.version}</version>
                <exclusions>
                    <exclusion>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-simple</artifactId>
                    </exclusion>
                </exclusions>
            </dependency>
            <!-- MySQL -->
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <scope>runtime</scope>
            </dependency>
            <!-- MyBatis-Plus -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <!-- Apache Tika -->
            <dependency>
                <groupId>org.apache.tika</groupId>
                <artifactId>tika-core</artifactId>
                <version>${tika.version}</version>
            </dependency>
            <dependency>
                <groupId>org.apache.tika</groupId>
                <artifactId>tika-parsers-standard-package</artifactId>
                <version>${tika.version}</version>
                <exclusions>
                    <exclusion>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-simple</artifactId>
                    </exclusion>
                </exclusions>
            </dependency>
            <!-- ILink SDK -->
            <dependency>
                <groupId>io.github.lith0924</groupId>
                <artifactId>wechat-ilink-sdk</artifactId>
                <version>${ilink-sdk.version}</version>
            </dependency>
            <!-- Spring Boot Web -->
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
            <!-- Actuator -->
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-actuator</artifactId>
            </dependency>
            <!-- Lombok -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
                <scope>provided</scope>
            </dependency>
            <!-- Test -->
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-test</artifactId>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>io.projectreactor</groupId>
                <artifactId>reactor-test</artifactId>
                <scope>test</scope>
            </dependency>
            <!-- JSON Schema Generator -->
            <dependency>
                <groupId>com.github.victools</groupId>
                <artifactId>jsonschema-generator</artifactId>
                <version>${jsonschema-generator.version}</version>
            </dependency>
            <dependency>
                <groupId>com.github.victools</groupId>
                <artifactId>jsonschema-module-jackson</artifactId>
                <version>${jsonschema-generator.version}</version>
            </dependency>
            <!-- Flyway -->
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-core</artifactId>
            </dependency>
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-mysql</artifactId>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>${lombok.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 2: Validate parent POM**

```bash
mvn validate
```

Expected: BUILD SUCCESS. The parent POM itself is valid; child modules don't exist yet, but `<modules>` listing them is fine for validate (Maven only resolves modules on `mvn compile` or explicit reactor builds).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "refactor: rewrite pom.xml as multi-module parent POM"
```

---

### Task 2: summer-common Module

**Files:**
- Create: `summer-common/pom.xml`
- Create: `summer-common/src/main/java/log/summer/common/exception/BotException.java`
- Create: `summer-common/src/main/java/log/summer/common/exception/AIServiceException.java`
- Create: `summer-common/src/main/java/log/summer/common/exception/ConfigurationException.java`
- Create: `summer-common/src/main/java/log/summer/common/exception/FileRecognitionException.java`
- Create: `summer-common/src/main/java/log/summer/common/exception/ImageGenerationException.java`
- Create: `summer-common/src/main/java/log/summer/common/exception/VoiceSynthesisException.java`
- Create: `summer-common/src/main/java/log/summer/common/enums/Timbre.java`
- Create: `summer-common/src/main/java/log/summer/common/enums/RouteContext.java`
- Create: `summer-common/src/main/java/log/summer/common/constant/Prompts.java`
- Create: `summer-common/src/main/resources/prompts/system.txt`
- Create: `summer-common/src/main/resources/prompts/file-system.txt`

**Interfaces:**
- Produces:
  - `BotException(String message)` and `BotException(String message, Throwable cause)` — abstract base for all bot exceptions, in package `log.summer.common.exception`
  - `AIServiceException(String operation, String message, Throwable cause)` extends `BotException`; `getOperation()` returns the operation name
  - `ConfigurationException(String message)` extends `BotException`
  - `FileRecognitionException(String stage, String message, Throwable cause)` extends `BotException`; `getStage()` returns stage
  - `ImageGenerationException(String stage, String message, Throwable cause)` extends `BotException`; `getStage()` returns stage
  - `VoiceSynthesisException(String message, Throwable cause)` extends `BotException`
  - `Timbre` enum with values from current `linkDemo.enums.Timbre`
  - `RouteContext` enum with values `TEXT`, `VOICE` (same as current `linkDemo.enums.RouteContext`)
  - `Prompts` constants class with `public static final String SYSTEM_PROMPT = "system"` and `public static final String FILE_SYSTEM_PROMPT = "file-system"`

- [ ] **Step 1: Create summer-common/pom.xml**

Minimal POM — no dependencies except Lombok (provided). Parent is `summer-dev`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>log.summer</groupId>
        <artifactId>summer-dev</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>summer-common</artifactId>
    <name>summer-common</name>
    <description>Shared POJO enums, constants, utilities, exceptions, and prompt templates</description>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Copy exception classes into summer-common with new package**

Create each exception file in `summer-common/src/main/java/log/summer/common/exception/`. Copy the body from the current `log.demo.linkDemo.exception.*` classes, changing only the package declaration to `package log.summer.common.exception;`. No other changes.

All six exception classes (`BotException`, `AIServiceException`, `ConfigurationException`, `FileRecognitionException`, `ImageGenerationException`, `VoiceSynthesisException`) retain their existing constructors, fields, and method signatures.

- [ ] **Step 3: Create enums in summer-common**

Copy `Timbre.java` and `RouteContext.java` from `log.demo.linkDemo.enums` to `log.summer.common.enums`. Change only the package declaration.

- [ ] **Step 4: Create Prompts.java constants class**

```java
package log.summer.common.constant;

/**
 * Prompt template key constants.
 * Actual template files live in summer-common/src/main/resources/prompts/.
 */
public final class Prompts {
    private Prompts() {}

    /** System prompt for the main chat model (qwen-plus) */
    public static final String SYSTEM_PROMPT = "system";

    /** System prompt for file analysis */
    public static final String FILE_SYSTEM_PROMPT = "file-system";
}
```

- [ ] **Step 5: Copy prompt resources**

Copy `src/main/resources/prompts/system.txt` and `src/main/resources/prompts/file-system.txt` from the root project's resources to `summer-common/src/main/resources/prompts/`.

- [ ] **Step 6: Verify summer-common compiles**

```bash
mvn compile -pl summer-common
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add summer-common/
git commit -m "feat: add summer-common module with exceptions, enums, constants, prompts"
```

---

### Task 3: summer-aigc Skeleton

**Files:**
- Create: `summer-aigc/pom.xml`
- Create: `summer-aigc/src/main/java/log/summer/aigc/port/BotInboundPort.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/port/BotMessage.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/port/MessageSender.java`

**Interfaces:**
- Consumes: `summer-common` (RouteContext enum)
- Produces:
  - `BotInboundPort` interface — `void onMessage(BotMessage msg)`
  - `BotMessage` record — `(String userId, String text, byte[] imageBytes, byte[] fileBytes, String fileName, RouteContext context)`
  - `MessageSender` interface — `void sendText(String userId, String content)`, `void sendImage(String userId, byte[] imageBytes, String filename, String description)`, `void sendFile(String userId, byte[] fileBytes, String filename, String description)`

- [ ] **Step 1: Create summer-aigc/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>log.summer</groupId>
        <artifactId>summer-dev</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>summer-aigc</artifactId>
    <name>summer-aigc</name>
    <description>Agent core engine: dual-loop orchestrator, tools, RAG, persistence</description>

    <dependencies>
        <dependency>
            <groupId>log.summer</groupId>
            <artifactId>summer-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>dashscope-sdk-java</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-parsers-standard-package</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create port interfaces**

Create `BotInboundPort.java`:

```java
package log.summer.aigc.port;

/**
 * Inbound port for bot message reception.
 * Transport adapters (ILink, Feishu, Discord) implement this interface.
 */
@FunctionalInterface
public interface BotInboundPort {
    void onMessage(BotMessage msg);
}
```

Create `BotMessage.java`:

```java
package log.summer.aigc.port;

import log.summer.common.enums.RouteContext;

/**
 * Normalized inbound message DTO — platform-agnostic.
 * All fields except userId are nullable.
 */
public record BotMessage(
    String userId,
    String text,
    byte[] imageBytes,
    byte[] fileBytes,
    String fileName,
    RouteContext context
) {
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0;
    }

    public boolean hasFile() {
        return fileBytes != null && fileBytes.length > 0;
    }
}
```

Create `MessageSender.java` — copy the existing interface from `log.demo.linkDemo.service.MessageSender`, changing only the package to `log.summer.aigc.port`.

- [ ] **Step 3: Verify summer-aigc compiles**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add summer-aigc/pom.xml summer-aigc/src/main/java/log/summer/aigc/port/
git commit -m "feat: add summer-aigc skeleton with port interfaces"
```

---

### Task 4: Loop Core Types (ThinkResult, ActResult, ArgumentResolver)

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/ThinkResult.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/ArgumentResolver.java`

**Interfaces:**
- Consumes: `BotMessage` (for extracting image/file bytes in ArgumentResolver)
- Produces:
  - `ThinkResult` record — `(String finalAnswer, List<ToolCall> toolCalls)`, inner record `ToolCall(String name, Map<String, Object> arguments)`. Static factory `fromChatResponse(ChatResponse response)` parses Spring AI response. `hasFinalAnswer()` and `hasToolCalls()` helper methods.
  - `ActResult` record — `(boolean success, Object data, String errorMessage)`. Static factories: `ActResult.success(Object data)` and `ActResult.failure(String errorMessage)`.
  - `ArgumentResolver` class — `Map<String, Object> resolve(Map<String, Object> rawArgs, BotMessage msg)`. Replaces placeholder strings with actual bytes.

- [ ] **Step 1: Create ThinkResult.java**

```java
package log.summer.aigc.loop;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.*;

/**
 * Encapsulates a single round of LLM thinking.
 * Parsed from Spring AI's {@link ChatResponse}.
 *
 * @param finalAnswer the text response to send to the user (nullable)
 * @param toolCalls   tools the LLM wants to invoke (nullable, empty list if none)
 */
public record ThinkResult(String finalAnswer, List<ToolCall> toolCalls) {

    public record ToolCall(String name, Map<String, Object> arguments) {}

    public boolean hasFinalAnswer() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Parse Spring AI ChatResponse into ThinkResult.
     * Extracts both text content and any function calls from the first generation.
     */
    public static ThinkResult fromChatResponse(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return new ThinkResult(null, List.of());
        }

        Generation gen = response.getResults().get(0);
        String text = gen.getOutput().getText();

        List<ToolCall> calls = new ArrayList<>();
        // Extract function calls from Spring AI's assistant message
        var assistantMsg = gen.getOutput();
        if (assistantMsg != null && assistantMsg.getToolCalls() != null) {
            for (var tc : assistantMsg.getToolCalls()) {
                Map<String, Object> args = new LinkedHashMap<>(tc.getArguments());
                calls.add(new ToolCall(tc.getName(), args));
            }
        }

        return new ThinkResult(
                (text != null && !text.isBlank()) ? text : null,
                calls
        );
    }
}
```

- [ ] **Step 2: Create ActResult.java**

```java
package log.summer.aigc.loop;

/**
 * Encapsulates the result of a tool execution.
 * Every @Tool method MUST return this type.
 *
 * @param success      true if the tool completed successfully
 * @param data         the tool's output data on success (nullable)
 * @param errorMessage human-readable error description on failure (nullable)
 */
public record ActResult(boolean success, Object data, String errorMessage) {

    public static ActResult success(Object data) {
        return new ActResult(true, data, null);
    }

    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage);
    }
}
```

- [ ] **Step 3: Create ArgumentResolver.java**

```java
package log.summer.aigc.loop;

import log.summer.aigc.port.BotMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Resolves placeholder values in LLM-generated tool arguments
 * with actual binary data from the inbound BotMessage.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li><code>${message.image}</code> → byte[] from msg.imageBytes()</li>
 *   <li><code>${message.file}</code> → byte[] from msg.fileBytes()</li>
 *   <li><code>${message.fileName}</code> → String from msg.fileName()</li>
 * </ul>
 */
@Slf4j
public class ArgumentResolver {

    private static final String IMAGE_PLACEHOLDER = "${message.image}";
    private static final String FILE_PLACEHOLDER = "${message.file}";
    private static final String FILE_NAME_PLACEHOLDER = "${message.fileName}";

    /**
     * Walk all argument values and replace placeholder strings with actual data.
     *
     * @param rawArgs the arguments generated by the LLM
     * @param msg     the original inbound message carrying binary data
     * @return resolved arguments ready for tool execution
     */
    public Map<String, Object> resolve(Map<String, Object> rawArgs, BotMessage msg) {
        Map<String, Object> resolved = new LinkedHashMap<>(rawArgs);

        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                switch (str) {
                    case IMAGE_PLACEHOLDER -> {
                        if (msg.hasImage()) {
                            entry.setValue(msg.imageBytes());
                        } else {
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.image} but no image in current message");
                        }
                    }
                    case FILE_PLACEHOLDER -> {
                        if (msg.hasFile()) {
                            entry.setValue(msg.fileBytes());
                        } else {
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.file} but no file in current message");
                        }
                    }
                    case FILE_NAME_PLACEHOLDER -> {
                        if (msg.fileName() != null) {
                            entry.setValue(msg.fileName());
                        } else {
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.fileName} but no fileName in current message");
                        }
                    }
                }
            }
        }

        return resolved;
    }

    /**
     * Thrown when a placeholder cannot be resolved.
     * Caught by AgentLoop → produces ActResult.failure → LLM self-corrects.
     */
    public static class PlaceholderResolutionException extends RuntimeException {
        public PlaceholderResolutionException(String message) {
            super(message);
        }
    }
}
```

- [ ] **Step 4: Verify summer-aigc compiles**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/
git commit -m "feat: add ThinkResult, ActResult, ArgumentResolver core types"
```

---

### Task 5: Entity & Mapper Migration

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/Conversation.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/Message.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/UserMemory.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/ImageRecord.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/FileRecord.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/IdiomGameRecord.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/WeatherQuery.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/TimbreChange.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/DocumentChunk.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/VoiceResult.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/ConversationMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/MessageMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/UserMemoryMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/ImageRecordMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/FileRecordMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/IdiomGameRecordMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/WeatherQueryMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/TimbreChangeMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/DocumentChunkMapper.java`

**Interfaces:**
- Produces: All 10 entity classes and 10 MyBatis-Plus mapper interfaces in package `log.summer.aigc.entity` and `log.summer.aigc.mapper`. Identical to current `log.demo.linkDemo.entity.*` and `log.demo.linkDemo.mapper.*` except for package declaration.

- [ ] **Step 1: Create entity package with all 10 entity classes**

For each current entity in `src/main/java/log/demo/linkDemo/entity/*.java`, copy to `summer-aigc/src/main/java/log/summer/aigc/entity/`. Change only the `package` declaration from `log.demo.linkDemo.entity` to `log.summer.aigc.entity`. Keep all imports, annotations (`@TableName`, `@Data`, `@TableId`, etc.), fields, and methods identical.

- [ ] **Step 2: Create mapper package with all 10 mapper interfaces**

For each current mapper in `src/main/java/log/demo/linkDemo/mapper/*.java`, copy to `summer-aigc/src/main/java/log/summer/aigc/mapper/`. Change only the `package` declaration to `log.summer.aigc.mapper`. Update entity imports from `log.demo.linkDemo.entity.*` to `log.summer.aigc.entity.*`. Keep all `extends BaseMapper<X>` and type parameters unchanged.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/entity/
git add summer-aigc/src/main/java/log/summer/aigc/mapper/
git commit -m "feat: migrate entities and mappers to summer-aigc"
```

---

### Task 6: Config & Properties Migration

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/config/AiConfig.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/config/BotProperties.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/config/VoiceProperties.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/config/HttpClientConfig.java`
- Create: `summer-aigc/src/main/resources/config/ai.yml`
- Create: `summer-aigc/src/main/resources/config/bot.yml`

**Interfaces:**
- Consumes: `summer-common` (Prompts constants)
- Produces:
  - `AiConfig` — two `ChatClient` beans: `chatClient` (qwen-plus, with ChatMemory advisor) and `intentChatClient` (qwen-turbo, now renamed `toolChatClient` since intent classification is replaced). `ChatMemoryRepository` and `ChatMemory` beans as before.
  - `BotProperties` — `@ConfigurationProperties("bot")` with nested `Cache`, `File`, `Weather` config classes
  - `VoiceProperties` — `@ConfigurationProperties("spring.ai.dashscope.voice")`
  - `HttpClientConfig` — HTTP client bean for external API calls

- [ ] **Step 1: Copy and adapt AiConfig.java**

Copy `src/main/java/log/demo/linkDemo/config/AiConfig.java` to `summer-aigc/src/main/java/log/summer/aigc/config/AiConfig.java`.

Changes:
- Package → `log.summer.aigc.config`
- Rename `@Qualifier("intentChatClient")` bean to `@Qualifier("toolChatClient")` (better name since we no longer do intent classification)
- Update system prompt path to reference `Prompts.SYSTEM_PROMPT`
- Keep `ChatClient chatClient` (qwen-plus, with ChatMemory advisor) unchanged
- Keep `ChatMemoryRepository`, `ChatMemory`, `MessageChatMemoryAdvisor` beans unchanged
- `@EnableConfigurationProperties` now references classes in `log.summer.aigc.config`

- [ ] **Step 2: Copy BotProperties and VoiceProperties**

Copy `BotProperties.java` and `VoiceProperties.java` from `log.demo.linkDemo.config` to `log.summer.aigc.config`. Change only the package declaration. Keep all `@ConfigurationProperties` annotations, nested classes, and fields.

- [ ] **Step 3: Copy HttpClientConfig**

Copy `HttpClientConfig.java` — package change only.

- [ ] **Step 4: Copy config YAMLs**

Copy `src/main/resources/config/ai.yml` and `src/main/resources/config/bot.yml` to `summer-aigc/src/main/resources/config/`. Update the system prompt path in ai.yml from `classpath:prompts/system.txt` to `classpath:prompts/system.txt` (same path since prompts moved to common which is on aigc's classpath).

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/config/
git add summer-aigc/src/main/resources/config/
git commit -m "feat: migrate config classes and YAMLs to summer-aigc"
```

---

### Task 7: Flyway Migration

**Files:**
- Create: `summer-aigc/src/main/resources/db/migration/V1__initial_schema.sql`
- Modify: `summer-aigc/src/main/resources/config/ai.yml` (remove `spring.sql.init.mode`, add Flyway config — actually this goes in application.yml in bootstrap later)

**Interfaces:**
- Produces: `V1__initial_schema.sql` — Flyway baseline migration containing all CREATE TABLE statements from the existing `schema.sql`

- [ ] **Step 1: Create V1__initial_schema.sql**

Copy the contents of `src/main/resources/schema.sql` into `summer-aigc/src/main/resources/db/migration/V1__initial_schema.sql`. No changes to the SQL — Flyway expects plain DDL.

- [ ] **Step 2: Commit**

```bash
git add summer-aigc/src/main/resources/db/migration/
git commit -m "feat: add Flyway V1 baseline migration"
```

---

### Task 8: RAG Service Migration

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/rag/DocumentChunkingService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/rag/EmbeddingService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/rag/RAGContextAugmenter.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/rag/RAGRetrievalService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/rag/VectorStoreService.java`

**Interfaces:**
- Produces: All 5 RAG service classes in `log.summer.aigc.rag`. Package-only change from their current `log.demo.linkDemo.rag` location.

- [ ] **Step 1: Copy all 5 RAG service files**

For each file in `src/main/java/log/demo/linkDemo/rag/*.java`, copy to `summer-aigc/src/main/java/log/summer/aigc/rag/`. Change:
- Package → `log.summer.aigc.rag`
- Update imports referencing `log.demo.linkDemo.*` → `log.summer.aigc.*` (entities, mappers, services)

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS (may depend on services from Task 9 — if so, this task compiles successfully only after Task 9).

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/rag/
git commit -m "feat: migrate RAG services to summer-aigc"
```

---

### Task 9: Service Layer Migration

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/ChatService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/ChatPersistenceService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IConversationService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IMessageService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IUserMemoryService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IImageRecordService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IFileRecordService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IIdiomGameRecordService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IWeatherQueryService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/ITimbreChangeService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IDocumentChunkService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/ConversationServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/MessageServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/UserMemoryServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/ImageRecordServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/FileRecordServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/IdiomGameRecordServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/WeatherQueryServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/TimbreChangeServiceImpl.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/DocumentChunkServiceImpl.java`

**Interfaces:**
- Produces: All 20 service files (10 interfaces + 10 implementations) in `log.summer.aigc.service`. Package-only change from `log.demo.linkDemo.service`.

- [ ] **Step 1: Copy all service interfaces and implementations**

For each file under `src/main/java/log/demo/linkDemo/service/*.java` and `src/main/java/log/demo/linkDemo/service/impl/*.java`, copy to corresponding path under `summer-aigc/src/main/java/log/summer/aigc/service/`.

Changes for all files:
- Package → `log.summer.aigc.service` (interfaces) or `log.summer.aigc.service.impl` (implementations)
- Update imports: `log.demo.linkDemo.entity.*` → `log.summer.aigc.entity.*`, `log.demo.linkDemo.mapper.*` → `log.summer.aigc.mapper.*`, `log.demo.linkDemo.exception.*` → `log.summer.common.exception.*`, `log.demo.linkDemo.config.*` → `log.summer.aigc.config.*`
- Remove any references to `ILinkBotService`, `MessageSender` (old package) — these will be re-added in a later task when we wire the new port interfaces

**ChatService.java** needs additional adaptation:
- Add method signatures for the AgentLoop's use:
  - `ChatResponse chatWithTools(List<Message> messages, List<FunctionCallback> tools)` — calls `chatClient.prompt().messages(messages).tools(tools).call()`
  - `ChatResponse chat(List<Message> messages)` — calls without tools (fallback)
  - `ChatResponse chatWithRAG(List<Message> messages, List<Document> docs)` — with RAG context
- The existing `chat(String userId, String text)` and `chatWithRAG(String userId, String text, List<Document> docs)` methods remain; they internally assemble the message list from DB history + long-term memory + text, then delegate to the new methods above

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/service/
git commit -m "feat: migrate service layer to summer-aigc"
```

---

### Task 10: ToolRegistry

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java`

**Interfaces:**
- Consumes: All Spring beans annotated with `@Tool` (Spring AI's `@Tool` annotation on methods)
- Produces:
  - `ToolRegistry` class — scans Spring context for beans with `@Tool`-annotated methods, builds `List<FunctionCallback>` for the LLM

- [ ] **Step 1: Create ToolRegistry.java**

```java
package log.summer.aigc.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.function.FunctionCallback;
import org.springframework.ai.tool.function.FunctionCallbacks;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Scans the Spring context for @Tool-annotated beans and builds
 * a combined FunctionCallback list for the LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private List<FunctionCallback> callbacks = List.of();

    @PostConstruct
    public void scan() {
        // Find all beans that have @Tool-annotated methods
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(
                org.springframework.ai.tool.annotation.Tool.class);

        // Also scan for beans whose class or methods have @Tool
        // Spring AI's FunctionCallbacks.fromBeans handles this
        List<FunctionCallback> found = new ArrayList<>();

        // Collect unique beans that contain @Tool methods
        Set<Object> uniqueBeans = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            for (var method : bean.getClass().getMethods()) {
                if (method.isAnnotationPresent(
                        org.springframework.ai.tool.annotation.Tool.class)) {
                    uniqueBeans.add(bean);
                    break;
                }
            }
        }

        if (!uniqueBeans.isEmpty()) {
            found = FunctionCallbacks.from(uniqueBeans.toArray());
        }

        this.callbacks = found;
        log.info("[TOOL-REGISTRY] Scanned {} tool beans → {} FunctionCallbacks",
                uniqueBeans.size(), found.size());
    }

    /**
     * Returns all FunctionCallbacks for injection into the LLM call.
     */
    public List<FunctionCallback> getCallbacks() {
        return callbacks;
    }

    /**
     * Execute a named tool with raw arguments.
     * Used by AgentLoop to execute a tool call from the LLM.
     */
    public ActResult execute(String toolName, Map<String, Object> arguments) {
        for (FunctionCallback callback : callbacks) {
            if (callback.getName().equals(toolName)) {
                try {
                    Object result = callback.call(arguments);
                    if (result instanceof ActResult ar) {
                        return ar;
                    }
                    return ActResult.success(result);
                } catch (Exception e) {
                    log.error("[TOOL-REGISTRY] Tool execution failed | tool={}", toolName, e);
                    return ActResult.failure(e.getMessage() != null
                            ? e.getMessage()
                            : "Tool execution failed: " + e.getClass().getSimpleName());
                }
            }
        }
        return ActResult.failure("Unknown tool: " + toolName);
    }
}
```

Wait — using `ActResult` here means `ToolRegistry` depends on `ActResult` which is in `loop/`. That's correct per our file structure.

Note: Spring AI's exact `FunctionCallback` and `@Tool` API depends on the version in use. The code above is a best-effort based on Spring AI 1.0.x patterns. If the actual API differs, adapt during implementation.

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java
git commit -m "feat: add ToolRegistry for @Tool bean scanning and execution"
```

---

### Task 11: Core Tools (weather, TTS, voice, image, file)

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/weather/WeatherTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/voice/TtsTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/voice/TimbreSession.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/voice/AudioTranscoder.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/voice/TTSEngine.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageGenTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageRecognitionTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageGenService.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageContextManager.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageCacheManager.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/file/FileTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/file/FileRecognitionService.java` (moved)

**Interfaces:**
- Consumes: `BotProperties`, `VoiceProperties`, `ActResult`, `MessageSender` (has-a, not is-a — tools send via MessageSender passed through during execution, or injected)
- Produces:
  - `WeatherTool` — `@Tool String weatherQuery(String city, String day)` → `ActResult`
  - `TtsTool` — `@Tool ActResult synthesize(String text)` → `ActResult` with WAV bytes in data
  - `TtsTool` — `@Tool ActResult switchVoice(String voice)` → `ActResult`
  - `ImageGenTool` — `@Tool ActResult generateImage(String prompt)` → `ActResult` with image bytes in data
  - `ImageRecognitionTool` — `@Tool ActResult recognizeImage(byte[] image)` → `ActResult` with description text
  - `ImageRecognitionTool` — `@Tool ActResult editImage(byte[] image, String editPrompt)` → `ActResult` with new image bytes
  - `FileTool` — `@Tool ActResult analyzeFile(byte[] fileBytes, String fileName)` → `ActResult` with analysis text
- Note: All tools that produce binary output (images, WAV) must send via `MessageSender` injected into the tool AND return `ActResult.success("sent")` — the LLM needs text confirmation, not raw bytes in its context.

- [ ] **Step 1: Convert WeatherAgent to WeatherTool**

Create `WeatherTool.java` in `summer-aigc/src/main/java/log/summer/aigc/tool/weather/`:

```java
package log.summer.aigc.tool.weather;

import log.summer.aigc.loop.ActResult;
import log.summer.aigc.config.BotProperties;
import log.summer.aigc.entity.WeatherQuery;
import log.summer.aigc.mapper.WeatherQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final BotProperties botProperties;
    private final HttpClient httpClient;
    private final WeatherQueryMapper weatherQueryMapper;
    private static final Gson GSON = new Gson();

    @Tool(description = "查询指定城市今天或明天的天气情况")
    public ActResult weatherQuery(
            @ToolParam(description = "城市名称，例如 '北京'、'上海'") String city,
            @ToolParam(description = "'today' 或 'tomorrow'") String day) {

        try {
            String ext = "base";
            if ("tomorrow".equals(day)) ext = "all";

            String url = String.format("%s?key=%s&city=%s&extensions=%s&output=JSON",
                    botProperties.getWeather().getBaseUrl(),
                    botProperties.getWeather().getApiKey(), city, ext);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // Parse + cache + return result
            // (copy existing parsing logic from WeatherAgent)
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            String status = json.get("status").getAsString();
            if (!"1".equals(status)) {
                return ActResult.failure("天气查询失败：" + json.get("info").getAsString());
            }

            String forecast = parseForecast(json);
            cacheQuery(city, day, forecast);

            return ActResult.success(forecast);
        } catch (Exception e) {
            log.error("[WEATHER] 查询失败 | city={} | day={}", city, day, e);
            return ActResult.failure("天气查询异常：" + e.getMessage());
        }
    }

    private String parseForecast(JsonObject json) {
        // Copy existing parsing logic from WeatherAgent
        // ...
        return "";
    }

    private void cacheQuery(String city, String day, String result) {
        WeatherQuery query = new WeatherQuery();
        query.setCity(city);
        query.setQueryTime(LocalDateTime.now());
        query.setResult(result);
        weatherQueryMapper.insert(query);
    }
}
```

- [ ] **Step 2: Convert VoiceGenAgent to TtsTool**

Create `TtsTool.java` with two `@Tool` methods: `synthesize(String text)` and `switchVoice(String voice)`. Both return `ActResult`. Move `TTSEngine`, `AudioTranscoder`, and `TimbreSession` from current `tools/voice/` to the new package `log.summer.aigc.tool.voice`. Package change only — implementation is unchanged.

- [ ] **Step 3: Convert ImageGenAgent + ImageRecognitionAgent to ImageGenTool + ImageRecognitionTool**

`ImageGenTool` — one `@Tool` method: `generateImage(String prompt)`.
`ImageRecognitionTool` — two `@Tool` methods: `recognizeImage(byte[] image)`, `editImage(byte[] image, String editPrompt)`.
Move `ImageGenService`, `ImageContextManager`, `ImageCacheManager` to the new package.

Both image tools must inject `MessageSender` to send binary results directly to the user, then return `ActResult.success("图片已发送")`.

- [ ] **Step 4: Convert FileAgent to FileTool**

One `@Tool` method: `analyzeFile(byte[] fileBytes, String fileName)` → `ActResult`. Move `FileRecognitionService` to the new package.

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/weather/
git add summer-aigc/src/main/java/log/summer/aigc/tool/voice/
git add summer-aigc/src/main/java/log/summer/aigc/tool/image/
git add summer-aigc/src/main/java/log/summer/aigc/tool/file/
git commit -m "feat: convert core Agents to @Tool implementations"
```

---

### Task 12: Utility Tools (idiom, reminder, navigation, memory)

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/idiom/IdiomGameTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/idiom/GameSession.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/idiom/IdiomDictionary.java` (moved)
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/reminder/ReminderTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/navigation/NavigationTool.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/memory/MemoryStatusTool.java`

**Interfaces:**
- Consumes: `ActResult`
- Produces:
  - `IdiomGameTool` — `@Tool ActResult idiomGame(String input)` → handles single round of idiom game. LLM passes the user's idiom text; the tool manages game state internally.
  - `ReminderTool` — `@Tool ActResult setReminder(String time, String message)` → `ActResult`
  - `NavigationTool` — `@Tool ActResult navigate(String destination)` → `ActResult`
  - `MemoryStatusTool` — `@Tool ActResult memoryStatus()` → `ActResult` with runtime metrics

- [ ] **Step 1: Convert IdiomGameService to IdiomGameTool**

Create `IdiomGameTool.java` with a single `@Tool` method `idiomGame(String input)`. Move `GameSession` and `IdiomDictionary` to the new package. The tool internally tracks per-user game state via `ConcurrentHashMap<String, GameSession>`. The LLM sees this as a tool — it passes the user's idiom input as the tool argument and gets back the game result (or error if input is invalid).

- [ ] **Step 2: Convert ReminderTools to ReminderTool**

One `@Tool` method: `setReminder(String time, String message)`. Simplify — current implementation may have multiple methods; consolidate into one.

- [ ] **Step 3: Convert NavigationTools to NavigationTool**

One `@Tool` method: `navigate(String destination)`. Returns location/direction info.

- [ ] **Step 4: Convert MemoryMonitorTools to MemoryStatusTool**

One `@Tool` method: `memoryStatus()`. Returns JVM memory metrics and bot uptime. Inject `BotMetrics` bean if available.

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/idiom/
git add summer-aigc/src/main/java/log/summer/aigc/tool/reminder/
git add summer-aigc/src/main/java/log/summer/aigc/tool/navigation/
git add summer-aigc/src/main/java/log/summer/aigc/tool/memory/
git commit -m "feat: convert utility Agents to @Tool implementations"
```

---

### Task 13: BotMetrics and Session State

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/BotMetrics.java` (moved from tools/BotMetrics)
- Note: `TimbreSession` and `ImageCacheManager` were already moved in Tasks 11 and 12

**Interfaces:**
- Produces:
  - `BotMetrics` — a `@Component` with message/error counters, uptime tracking, and methods like `recordMessage()`, `recordError()`, `getStats()`. Moved from current `tools/BotMetrics` with package change only.

- [ ] **Step 1: Copy BotMetrics**

Copy `src/main/java/log/demo/linkDemo/tools/BotMetrics.java` to `summer-aigc/src/main/java/log/summer/aigc/tool/BotMetrics.java`. Package change only.

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/BotMetrics.java
git commit -m "feat: migrate BotMetrics to summer-aigc"
```

---

### Task 14: AgentLoop Orchestrator

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java`

**Interfaces:**
- Consumes: `ChatService`, `ToolRegistry`, `ArgumentResolver`, `ChatMemory`, `GlobalExceptionHandler`, `ThinkResult`, `ActResult`, `BotMessage`, `MessageSender`
- Produces:
  - `AgentLoop` — `public void orchestrate(BotMessage msg, MessageSender sender)`. Core orchestration method that runs the dual-loop until termination or safety valve.

- [ ] **Step 1: Create AgentLoop.java**

```java
package log.summer.aigc.loop;

import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatService;
import log.summer.aigc.tool.ToolRegistry;
import log.summer.common.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Dual-loop agent orchestrator.
 *
 * <h3>Flow</h3>
 * <pre>
 * while (round < maxIterations && !timeout):
 *   THINK: LLM → ThinkResult
 *   if finalAnswer → send to user, exit
 *   if toolCalls → ACT: execute each tool, append results to memory
 *   repeat
 * </pre>
 *
 * <p>Spring AI is used only for single-call interactions.
 * This class owns the iteration, memory, and termination logic.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final ArgumentResolver argumentResolver;
    private final ChatMemory chatMemory;
    private final GlobalExceptionHandler exceptionHandler;

    private static final int MAX_ITERATIONS = 10;
    private static final long TIMEOUT_SECONDS = 120;
    private static final String DEFAULT_CONVERSATION_ID = "default";

    /**
     * Orchestrate a single user message through the think-act loop.
     */
    public void orchestrate(BotMessage msg, MessageSender sender) {
        String userId = msg.userId();
        String conversationId = DEFAULT_CONVERSATION_ID;

        Instant start = Instant.now();
        int round = 0;

        // Add user message to memory
        if (msg.hasText()) {
            chatMemory.add(conversationId,
                    new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(conversationId,
                    new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(conversationId,
                    new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }

        try {
            while (round < MAX_ITERATIONS) {
                // ── Safety valve: timeout ──
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 超时 | userId={} | rounds={}", userId, round);
                    break;
                }

                round++;
                log.debug("[AGENT-LOOP] 第 {} 轮思考 | userId={}", round, userId);

                // ── THINK ──
                // Build message list from ChatMemory
                List<Message> messages = new ArrayList<>(
                        chatMemory.get(conversationId, Integer.MAX_VALUE));

                var chatResponse = chatService.chatWithTools(
                        messages,
                        toolRegistry.getCallbacks());

                ThinkResult think = ThinkResult.fromChatResponse(chatResponse);

                // ── Final answer → exit ──
                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    log.debug("[AGENT-LOOP] 最终答案 | userId={} | rounds={}", userId, round);
                    break;
                }

                // ── Final answer + tool calls (LLM can do both) ──
                if (think.hasFinalAnswer() && think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                }

                // ── ACT: execute tool calls ──
                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        log.info("[AGENT-LOOP] 调用工具 | userId={} | tool={} | args={}",
                                userId, toolCall.name(), toolCall.arguments());

                        ActResult result;
                        try {
                            Map<String, Object> resolvedArgs =
                                    argumentResolver.resolve(toolCall.arguments(), msg);
                            result = toolRegistry.execute(toolCall.name(), resolvedArgs);
                        } catch (ArgumentResolver.PlaceholderResolutionException e) {
                            result = ActResult.failure(e.getMessage());
                        }

                        // Append tool result to memory as a function message
                        String resultText = result.success()
                                ? (result.data() != null ? result.data().toString()
                                        : "success")
                                : "ERROR: " + result.errorMessage();

                        chatMemory.add(conversationId,
                                new ToolResponseMessage(toolCall.name(), resultText));

                        log.debug("[AGENT-LOOP] 工具结果 | userId={} | tool={} | success={}",
                                userId, toolCall.name(), result.success());
                    }
                    // Continue loop → next think round with tool results in context
                    continue;
                }

                // No tool calls and no final answer → safety break
                log.warn("[AGENT-LOOP] LLM 返回空响应 | userId={} | round={}", userId, round);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            // ── Max iterations exceeded ──
            if (round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId,
                        "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop", e);
        } finally {
            // Clear conversation memory
            chatMemory.clear(conversationId);
        }
    }
}
```

Note: `ToolResponseMessage` is used to feed tool results back. The exact Spring AI class name for this may be `ToolResponseMessage` or `FunctionMessage` depending on the version — adapt during implementation. The concept is: append the tool's output as a message so the LLM sees it in the next round.

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -pl summer-aigc
```

Expected: BUILD SUCCESS. If Spring AI API mismatches exist, fix class names.

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java
git commit -m "feat: implement AgentLoop dual-loop orchestrator"
```

---

### Task 15: SlashInterceptor

**Files:**
- Create: `summer-bot/pom.xml`
- Create: `summer-bot/src/main/java/log/summer/bot/SlashInterceptor.java`

**Interfaces:**
- Consumes: `BotMessage`, `MessageSender`, `BotMetrics`
- Produces:
  - `SlashInterceptor` — `boolean intercept(BotMessage msg, MessageSender sender)`. Returns `true` if the message was consumed.

- [ ] **Step 1: Create summer-bot/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>log.summer</groupId>
        <artifactId>summer-dev</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>summer-bot</artifactId>
    <name>summer-bot</name>
    <description>Transport adapters: ILink, slash commands, retry sending</description>

    <dependencies>
        <dependency>
            <groupId>log.summer</groupId>
            <artifactId>summer-aigc</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.lith0924</groupId>
            <artifactId>wechat-ilink-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create SlashInterceptor.java**

```java
package log.summer.bot;

import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.tool.BotMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pre-intercepts slash commands before they reach AgentLoop.
 * Returns true if the message was handled and should NOT be forwarded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlashInterceptor {

    private final BotMetrics botMetrics;

    /**
     * @return true if intercepted (message consumed), false to forward to AgentLoop
     */
    public boolean intercept(BotMessage msg, MessageSender sender) {
        if (!msg.hasText() || !msg.text().startsWith("/")) {
            return false;
        }

        String text = msg.text().trim();
        String userId = msg.userId();

        switch (text) {
            case "/help" -> {
                sender.sendText(userId, """
                        【可用命令】
                        /help    — 查看帮助
                        /status  — 查看机器人运行状态
                        /cancel  — 取消当前会话
                        
                        【功能】
                        • 天气查询：今天北京的天气怎么样？
                        • 图片生成：帮我画一只猫
                        • 图片识别：发送图片让我识别
                        • 语音合成：用语音朗读一段文字
                        • 文件分析：发送 PDF/Word 让我分析
                        • 成语接龙：跟我玩成语接龙
                        • 设置提醒：明天下午3点提醒我开会
                        """);
            }
            case "/status" -> {
                var stats = botMetrics.getStats();
                sender.sendText(userId, String.format("""
                        【运行状态】
                        运行时长：%s
                        已处理消息：%d
                        错误数：%d
                        """, stats.uptime(), stats.messageCount(), stats.errorCount()));
            }
            case "/cancel" -> {
                sender.sendText(userId, "当前会话已取消。开始新的对话吧！");
                // AgentLoop will clear ChatMemory on termination
            }
            default -> {
                if (text.startsWith("/")) {
                    sender.sendText(userId, "未知命令，输入 /help 查看可用命令");
                }
            }
        }

        return true;
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl summer-bot
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add summer-bot/
git commit -m "feat: add summer-bot module with SlashInterceptor"
```

---

### Task 16: ILinkBotAdapter + RetrySender

**Files:**
- Create: `summer-bot/src/main/java/log/summer/bot/ILinkBotAdapter.java`
- Create: `summer-bot/src/main/java/log/summer/bot/RetrySender.java`

**Interfaces:**
- Consumes: `BotInboundPort`, `BotMessage`, `MessageSender`, `SlashInterceptor`, `AgentLoop`, `BotProperties`, `BotMetrics`, `ChatPersistenceService`
- Produces:
  - `ILinkBotAdapter` — implements `BotInboundPort`, `MessageSender`. Migrates all ILink logic from the current `ILinkBotService`. On message received: `SlashInterceptor.intercept()` → if not consumed: `AgentLoop.orchestrate()`. As `MessageSender`: wraps `ILinkClient` send methods with retry via `RetrySender`.
  - `RetrySender` — utility class with `sendWithRetry(String userId, Runnable sendAction, String mediaType, String filename)`. Exponential backoff: 1s → 2s → 4s, max 3 attempts.

- [ ] **Step 1: Create RetrySender.java**

Extract the `sendWithRetry` method from the current `ILinkBotService` into its own class:

```java
package log.summer.bot;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RetrySender {

    private static final int MAX_RETRIES = 2; // 3 total attempts (0, 1, 2)

    public void sendWithRetry(String userId, Runnable sendAction,
                              String mediaType, String filename,
                              java.util.function.BiConsumer<String, String> fallbackSender) {
        long backoffMs = 1000;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                sendAction.run();
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[SEND] 发送{}失败，{}/{} 秒后重试 | userId={} | file={} | error={}",
                            mediaType, attempt + 1, MAX_RETRIES, userId, filename, e.getMessage());
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
                    backoffMs *= 2;
                } else {
                    log.error("[SEND] 发送{}失败（已重试{}次）| userId={} | file={}",
                            mediaType, MAX_RETRIES, userId, filename, e);
                    fallbackSender.accept(userId,
                            String.format("%s发送失败（已重试），请稍后再试", mediaType));
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create ILinkBotAdapter.java**

Migrate the entire `ILinkBotService` logic into `ILinkBotAdapter`. Key changes:

1. It implements `BotInboundPort` and `MessageSender`
2. The `init()` method (ILink connection startup) stays identical
3. `handleMessage(WeixinMessage)` → builds `BotMessage` (instead of `AgentContext`), then calls `SlashInterceptor.intercept()` → if false: `AgentLoop.orchestrate()`
4. `handleImageMessage` → downloads image bytes, builds `BotMessage` with `imageBytes`, routes to AgentLoop
5. `handleVoiceMessage` → extracts ASR text, builds `BotMessage` with `RouteContext.VOICE`, routes to AgentLoop
6. `handleFileMessage` → downloads file bytes, builds `BotMessage` with `fileBytes` + `fileName`, routes to AgentLoop
7. `MessageSender` methods → uses `RetrySender.sendWithRetry()` for image/file, direct `ILinkClient.sendTextWithTyping()` for text
8. Persistence: `saveUserMessage` and `saveBotMessage` → use `ChatPersistenceService` from `summer-aigc`

The class is ~350 lines. Write the complete file — do not reference or delegate to the old `ILinkBotService`.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl summer-bot
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add summer-bot/src/main/java/log/summer/bot/ILinkBotAdapter.java
git add summer-bot/src/main/java/log/summer/bot/RetrySender.java
git commit -m "feat: implement ILinkBotAdapter and RetrySender"
```

---

### Task 17: Bootstrap Module — Wiring Everything

**Files:**
- Create: `summer-bootstrap/pom.xml`
- Create: `summer-bootstrap/src/main/java/log/summer/bootstrap/SummerApplication.java`
- Create: `summer-bootstrap/src/main/resources/application.yml`
- Create: `summer-bootstrap/src/main/resources/config/security.yml` (placeholder)

**Interfaces:**
- Consumes: `summer-aigc`, `summer-bot`, `summer-common`
- Produces:
  - `SummerApplication` — `@SpringBootApplication` with `@MapperScan("log.summer.aigc.mapper")` and `@ComponentScan(basePackages = {"log.summer.common", "log.summer.aigc", "log.summer.bot"})`
  - `application.yml` — composited config with `spring.config.import` pointing to aigc configs

- [ ] **Step 1: Create summer-bootstrap/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>log.summer</groupId>
        <artifactId>summer-dev</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>summer-bootstrap</artifactId>
    <name>summer-bootstrap</name>
    <description>Application launcher: @SpringBootApplication, assembly, and runtime config</description>

    <dependencies>
        <dependency>
            <groupId>log.summer</groupId>
            <artifactId>summer-bot</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>log.summer</groupId>
            <artifactId>summer-aigc</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>log.summer</groupId>
            <artifactId>summer-common</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create SummerApplication.java**

```java
package log.summer.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("log.summer.aigc.mapper")
@ComponentScan(basePackages = {
        "log.summer.common",
        "log.summer.aigc",
        "log.summer.bot"
})
public class SummerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SummerApplication.class, args);
    }
}
```

- [ ] **Step 3: Create application.yml**

Compose from the existing root `application.yml`. Key changes:
- `spring.sql.init.mode: always` → **removed** (Flyway handles this now)
- `spring.flyway.enabled: true`, `spring.flyway.locations: classpath:db/migration`
- `spring.config.import` → `optional:classpath:config/security.yml, classpath:config/ai.yml, classpath:config/bot.yml` (ai.yml and bot.yml are on classpath via summer-aigc dependency)
- All existing datasource, mybatis-plus, management configs remain

```yaml
server:
  port: 8080

spring:
  application:
    name: summer-bot
  config:
    import:
      - optional:classpath:config/security.yml
      - classpath:config/ai.yml
      - classpath:config/bot.yml

  datasource:
    url: jdbc:mysql://localhost:3306/wxbot_db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver

  flyway:
    enabled: true
    locations: classpath:db/migration

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

management:
  endpoints:
    web:
      exposure:
        include: health,metrics
```

- [ ] **Step 4: Create placeholder security.yml**

```yaml
# Security keys — gitignored. Fill in your actual keys.
# Copy from existing config/security.yml
```

- [ ] **Step 5: Full build**

```bash
mvn clean compile -pl summer-dev
```

Expected: BUILD SUCCESS across all 5 modules (parent + 4 children).

- [ ] **Step 6: Commit**

```bash
git add summer-bootstrap/
git commit -m "feat: add summer-bootstrap module with launcher and config"
```

---

### Task 18: Cleanup — Remove Old Classes

**Files:**
- Delete: `src/main/java/log/demo/linkDemo/` (entire old package tree)
- Delete: `src/main/resources/` (old resources — prompts, configs, schema.sql)
- Delete: `src/test/` (old test — rewrite in future task)
- Delete: `pom.xml` (is now parent POM, old single-module build section removed in Task 1 — verify no stale content)
- Modify: `.gitignore` (if needed)

**Interfaces:**
- None produced. Pure deletion.

- [ ] **Step 1: Remove old source tree**

```bash
rm -rf src/main/java/log/demo/
rm -rf src/main/resources/
rm -rf src/test/
```

- [ ] **Step 2: Verify full build still passes**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS. The old code is gone; only the new modules compile.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove old single-module code"
```

---

### Task 19: End-to-End Verification

**Files:**
- No new files. Runtime verification only.

- [ ] **Step 1: Start the application**

```bash
mvn spring-boot:run -pl summer-bootstrap
```

Expected: Application starts without errors. Check logs for:
- `[TOOL-REGISTRY] Scanned N tool beans → N FunctionCallbacks`
- `[BOT] 正在启动微信机器人...`
- `[FLYWAY] Successfully applied migration`

- [ ] **Step 2: Verify slash commands work**

Send `/help`, `/status`, `/cancel` via WeChat. Expected: each command returns the correct response without calling the LLM.

- [ ] **Step 3: Verify single-tool requests**

Send "今天北京天气怎么样". Expected: LLM calls `weather_query` tool, tool returns weather data, LLM formats and presents it.

- [ ] **Step 4: Verify multi-step requests**

Send "查一下上海今天天气，然后根据天气情况生成一张对应风格的图片". Expected: LLM calls `weather_query` → receives result → calls `generateImage` with weather-appropriate prompt → both operations complete in one session.

- [ ] **Step 5: Verify tool failure self-correction**

Send a request that would cause tool failure (e.g. invalid weather city "不存在的城市"). Expected: Tool returns `ActResult.failure(...)`, LLM reads the error and tells the user gracefully — no stack trace.

- [ ] **Step 6: Verify chat/RAG fallback**

Send a casual chat message like "你好，介绍一下你自己". Expected: No tool calls, LLM returns a text introduction.

- [ ] **Step 7: Commit any fixes found during verification**

---

## Dependency Summary

| Task | Depends On | Produces For |
|------|-----------|--------------|
| 1 Parent POM | — | 2-17 |
| 2 summer-common | 1 | 3-17 |
| 3 summer-aigc skeleton | 1, 2 | 4-14 |
| 4 Loop core types | 3 | 10, 14 |
| 5 Entity + Mapper | 3 | 6, 8, 9, 11, 12 |
| 6 Config | 3 | 9, 11, 14 |
| 7 Flyway | 3 | 19 (runtime) |
| 8 RAG | 3, 5 | 9, 14 |
| 9 Service layer | 3, 5, 6, 8 | 14, 16 |
| 10 ToolRegistry | 3, 4 | 11, 12, 14 |
| 11 Core tools | 3, 4, 5, 6, 9, 10 | 14, 19 |
| 12 Utility tools | 3, 4, 5, 10 | 14, 19 |
| 13 BotMetrics | 3 | 15 |
| 14 AgentLoop | 3, 4, 6, 8, 9, 10, 11, 12 | 16, 19 |
| 15 SlashInterceptor | 3 (port), 13 | 16 |
| 16 ILinkBotAdapter | 3, 14, 15 | 17, 19 |
| 17 Bootstrap | 1-16 | 19 |
| 18 Cleanup | 17 | 19 |
| 19 E2E verification | 1-18 | — |
