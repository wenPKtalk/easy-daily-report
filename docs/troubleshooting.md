# Easy Daily Report - 故障排查与修复记录

> **日期:** 2026-04-29  
> **状态:** 已解决  

---

## 问题总览

执行 `./gradlew test` 时，连续遇到三个启动失败错误，涉及 Spring Shell 注解配置、LangChain4j 依赖兼容性和测试环境配置。

---

## 错误 1: AnnotationConfigurationException

### 症状

```
AnnotationConfigurationException: Different @AliasFor mirror values 
for annotation [Command] declared on generateReport;
attribute 'description' and its alias 'value' are declared with 
values of [生成工作日报] and [report generate].
```

### 根因分析

Spring Shell 4.0.1 的 `@Command` 注解中，`value` 和 `description` 属性通过 `@AliasFor` 互为镜像别名（alias）。这意味着它们本质上是**同一个字段的两个名字**，必须保持相同的值，不能分别设置不同内容。

查看 `@Command` 源码定义：

```java
@AliasFor("description")
String value() default "";

@AliasFor("value")
String description() default "";
```

### 错误代码

```java
// ❌ 错误：value 和 description 被赋了不同的值
@Command(value = "report generate", description = "生成工作日报", group = "Daily Report")
public String generateReport(...) { }
```

### 修复方案

移除 `description` 参数，只保留 `value`（命令名称）：

```java
// ✅ 正确：只使用 value
@Command(value = "report generate", group = "Daily Report")
public String generateReport(...) { }
```

### 文件变更

`@/Users/pengkunwen/tech/easy-daily-report/src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java`

---

## 错误 2: ClassNotFoundException (RestClient 相关)

### 症状

```
ClassNotFoundException: org.springframework.boot.http.client.ClientHttpRequestFactorySettings

ClassNotFoundException: org.springframework.web.client.RestClient$Builder
```

### 根因分析

LangChain4j 的 `langchain4j-open-ai-spring-boot-starter` 默认引入 `langchain4j-http-client-spring-restclient`，该模块依赖 Spring Boot 的 `RestClient` 和 `ClientHttpRequestFactorySettings`。

**冲突点：**
- Spring Boot 4.x 是非 Web 应用（`spring-boot-starter` 不含 `spring-web`）
- `ClientHttpRequestFactorySettings` 在 Spring Boot 4.x 中已被移除/重构
- `RestClient` 位于 `spring-web` 模块，非 Web 项目 classpath 中不存在

依赖传递链：
```
langchain4j-open-ai-spring-boot-starter
    └── langchain4j-http-client-spring-restclient
            └── 依赖 spring-web (RestClient)
```

### 修复方案

**方案 A（采用）：** 移除 Spring Boot Starter，直接使用 Core 模块

```gradle
// ❌ 移除这些依赖
implementation 'dev.langchain4j:langchain4j-spring-boot-starter'
implementation 'dev.langchain4j:langchain4j-open-ai-spring-boot-starter'

// ✅ 改用 core 模块
implementation 'dev.langchain4j:langchain4j-open-ai'
```

**方案 B（备选）：** 如果必须使用 Starter，添加 `spring-web` 依赖

```gradle
implementation 'org.springframework:spring-web'
```

### 额外修复：手动创建 ChatModel Bean

移除 Starter 后失去 auto-configuration，需在 `LangChain4jConfig.java` 中手动创建 `ChatModel`：

```java
@Bean
public ChatModel chatModel(
        @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
        @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
        @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
        @Value("${langchain4j.open-ai.chat-model.temperature:0.3}") double temperature
) {
    return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(temperature)
            .build();
}
```

### 配置文件更新

`@/Users/pengkunwen/tech/easy-daily-report/src/main/resources/application.yaml`

添加 `base-url` 支持 ZhipuAI/GLM 等 OpenAI 兼容 API：

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: ${LLM_BASE_URL:https://open.bigmodel.cn/api/paas/v4/}
      api-key: ${OPENAI_API_KEY}
      model-name: ${LLM_MODEL:glm-4-flash}
```

### 文件变更

- `@/Users/pengkunwen/tech/easy-daily-report/build.gradle` — 替换依赖
- `@/Users/pengkunwen/tech/easy-daily-report/src/main/java/com/topsion/easy_daily_report/infrastructure/config/LangChain4jConfig.java` — 添加 chatModel Bean

---

## 错误 3: BeanCreationException (测试时连接 PGVector)

### 症状

```
BeanCreationException: Error creating bean with name 'embeddingStore': 
Factory method 'embeddingStore' threw exception with message: Failed to execute 'init'
```

### 根因分析

`@SpringBootTest` 加载完整的 Spring ApplicationContext，包括 `PgVectorConfig` 中的 `EmbeddingStore` bean。该 bean 在初始化时会尝试连接 PostgreSQL 数据库创建表，但测试环境未启动 PGVector 容器。

### 修复方案

使用 `@MockitoBean`（Spring Boot 3.4+）或 `@MockBean` 在测试中 mock 外部依赖：

```java
@SpringBootTest
class EasyDailyReportApplicationTests {

    @MockitoBean
    private EmbeddingStore<TextSegment> embeddingStore;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void contextLoads() {
        // 测试通过：上下文加载成功，无需真实数据库/LLM
    }
}
```

### 文件变更

`@/Users/pengkunwen/tech/easy-daily-report/src/test/java/com/topsion/easy_daily_report/EasyDailyReportApplicationTests.java`

---

## 修复清单汇总

| 文件 | 变更内容 | 解决错误 |
|------|---------|---------|
| `DailyReportCommands.java` | 移除 `@Command` 的 `description` 参数 | #1 AnnotationConfigurationException |
| `build.gradle` | 替换 `langchain4j-*-spring-boot-starter` 为 `langchain4j-open-ai` | #2 RestClient ClassNotFound |
| `LangChain4jConfig.java` | 添加 `chatModel()` @Bean 方法 | #2 手动创建 Bean |
| `application.yaml` | 添加 `base-url` 配置 | #2 支持 ZhipuAI |
| `EasyDailyReportApplicationTests.java` | 添加 `@MockitoBean` for EmbeddingStore/ChatModel | #3 BeanCreationException |

---

## 验证命令

```bash
# 清理构建
./gradlew clean

# 运行测试
./gradlew test

# 预期输出
BUILD SUCCESSFUL
```

---

## 相关参考

- [Spring Shell 4.0 注解文档](https://spring.io/projects/spring-shell)
- [LangChain4j GitHub Issues - Spring Boot 兼容性问题](https://github.com/langchain4j/langchain4j/issues)
- [Spring Boot 3.4 @MockitoBean 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing.spring-boot-applications.mocking-beans)
