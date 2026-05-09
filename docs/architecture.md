# jAgent × Spring AI 调用链路

> 记录从 CLI 输入 → `ChatClient` → 模型 → 工具执行 → 多轮历史回写 的完整链路。
> 基于 **Spring AI 1.1.6** 源码走读 + 本项目实际代码（`AgentService` / `CliRunner`）。

---

## 0. 一图概览

```
┌────────────────┐   user text
│  CliRunner     │──────────────────────────────┐
│ (REPL)         │                              ▼
└────────────────┘                     ┌─────────────────┐
                                       │  AgentService   │
                                       │  .chat(input)   │
                                       └────────┬────────┘
                                                │ ChatClient.prompt().user().call()
                                                ▼
                               ┌────────────────────────────────┐
                               │      ChatClient (门面)         │
                               │  组装 ChatClientRequest        │
                               │  构建 AdvisorChain             │
                               └────────────────┬───────────────┘
                                                │ chain.nextCall(request)
                                                ▼
                       ┌────────────────────────────────────────────┐
                       │   AdvisorChain (责任链)                    │
                       │                                            │
                       │   [ MessageChatMemoryAdvisor.before ]      │  1) 读历史 → 拼进 messages
                       │                    │                       │
                       │                    ▼                       │
                       │   [ (其它自定义 advisor) ]                 │
                       │                    │                       │
                       │                    ▼                       │
                       │   [ ChatModelCallAdvisor ] ─┐              │  终点 advisor
                       │                             │              │
                       │   [ MessageChatMemoryAdvisor.after ]◄──┐   │  3) 写回最终 AssistantMessage
                       └─────────────────────────────┼──────────┼───┘
                                                     │          │
                                                     ▼          │
                                            ┌──────────────┐    │
                                            │ OpenAiChatModel│   │  2) 调模型 + 工具循环
                                            │  .call()      │   │
                                            └───────┬───────┘   │
                                                    │           │
                                        HTTP POST /v1/chat/completions
                                                    │
                                                    ▼
                                       ┌────────────────────────┐
                                       │  opencode zen / OpenAI │
                                       └────────────────────────┘
                                                    │ assistant 消息(含 tool_calls)
                                                    ▼
                                       ┌────────────────────────┐
                                       │ ToolCallingManager     │
                                       │  .executeToolCalls()   │
                                       └───────┬────────────────┘
                                               │ 按 tool name 找 ToolCallback
                                               ▼
                                 ┌────────────────────────────────┐
                                 │ MethodToolCallback.call(json)  │
                                 │  反射调用 @Tool 方法           │
                                 │  → ShellExecSkill.execute(...) │
                                 └────────────────────────────────┘
```

---

## 1. 启动阶段：Skill 如何被装配

**代码位置**：`AgentService.kt`

```kotlin
@Service
class AgentService(
    chatClientBuilder: ChatClient.Builder,
    applicationContext: ApplicationContext,
) {
    private val skillBeans = applicationContext.getBeansWithAnnotation(Skill::class.java)

    private val toolCallbackProvider: ToolCallbackProvider =
        MethodToolCallbackProvider.builder()
            .toolObjects(*skillBeans.values.toTypedArray())
            .build()

    private val chatClient = chatClientBuilder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultToolCallbacks(toolCallbackProvider)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build()
}
```

### 1.1 `MethodToolCallbackProvider` 做了什么

启动时扫描所有 `toolObjects` 里的 bean，用反射找出每个类里标了 `@Tool` 的方法，为每个方法生成一个 `MethodToolCallback`：

| 字段 | 来源 | 用途 |
|---|---|---|
| `toolDefinition.name` | 方法名（或 `@Tool(name=...)`） | 发给模型的函数名，如 `execute`、`readFile` |
| `toolDefinition.description` | `@Tool(description=...)` | 告诉模型这个工具干什么 |
| `toolDefinition.inputSchema` | 从方法参数 + `@ToolParam` 自动生成 JSON Schema | 模型按此格式填参数 |
| `toolMethod` | `java.lang.reflect.Method` | 真正调用时反射拿到 |
| `toolObject` | 对应的 Spring bean | 反射 invoke 的实例 |

这些 `ToolCallback` 被塞进 `ChatClient` 的 *default options*，之后每次 `.prompt().call()` 都会带上。

### 1.2 本项目自定义的 `@Skill` 只是「入口标记」

`@Skill` 不是 Spring AI 的概念，只是我们用来**筛选哪些 bean 需要被 `MethodToolCallbackProvider` 扫描**。真正被模型识别的是类里面方法上的 `@Tool`。

---

## 2. 一次 `chat()` 调用的完整链路

### 2.1 入口：`ChatClient.prompt().user(...).call().content()`

**调用栈**：
```
ChatClient.prompt()
   └─ DefaultChatClientRequestSpec            ← 收集 user/system/tools/advisor 参数
        └─ .user(text) / .advisors{...}
        └─ .call()
             └─ DefaultCallResponseSpec
                  └─ .content()
                        └─ doGetObservableChatClientResponse(request)
                              └─ advisorChain.nextCall(request)   ← 进入 advisor 链
```

`DefaultChatClientRequestSpec` 把调用者填入的所有东西合成一个 `ChatClientRequest`：
- `prompt`：`SystemMessage` + `UserMessage`
- `context`：一个 `Map<String,Object>`，放 advisor 的运行期参数（**`chat_memory_conversation_id` 就放这里**）

### 2.2 AdvisorChain 的责任链

`DefaultAroundAdvisorChain` 内部是一个 `Deque<CallAdvisor>`，`nextCall()` 每次 `pollFirst()` 取下一个 advisor 执行。顺序（根据 `Order` 排序）大致是：

```
用户添加的 advisor（按 @Order）
      │
      ▼
MessageChatMemoryAdvisor   (@Order = HIGHEST_PRECEDENCE + 1000，很靠前)
      │
      ▼
   …其它…
      │
      ▼
ChatModelCallAdvisor       (@Order = LOWEST_PRECEDENCE，链尾)  ← 真正调模型
```

每个 advisor 实现 `adviseCall(request, chain)`：
- `before` 阶段：改 request（比如塞历史消息）
- 调 `chain.nextCall(request)` 继续往下
- `after` 阶段：拿到 response 后做收尾（比如写回 memory）

### 2.3 `MessageChatMemoryAdvisor` — 多轮对话的核心

**源码关键路径**：
```
MessageChatMemoryAdvisor.before(request, chain)
   └─ getConversationId(request.context())         ← 从 context 里取 chat_memory_conversation_id
   └─ ChatMemory.get(conversationId)               ← 返回历史消息 List<Message>
   └─ 把历史拼到 prompt 的 messages 前面
   └─ 返回新的 ChatClientRequest

...chain.nextCall(request) 一路往下、调完模型回来...

MessageChatMemoryAdvisor.after(response, chain)
   └─ 从 response 里拿最后一条 AssistantMessage
   └─ ChatMemory.add(conversationId, [UserMessage, AssistantMessage])
```

**两个关键点**：

1. **`conversationId` 必须显式传**。
   ```kotlin
   chatClient.prompt()
       .user(userInput)
       .advisors { it.param(ChatMemory.CONVERSATION_ID, "cli-session") }  // ← 必须
       .call()
   ```
   不传就会在 `getConversationId()` 的 `Assert.notNull` 抛 `conversationId cannot be null`（这正是我们之前踩过的坑）。

2. **只会保存「用户消息 + 最终 assistant 消息」**。
   Tool call 的中间回合（assistant 的 `tool_calls` 请求、tool 返回的 `ToolResponseMessage`）**不会进 ChatMemory**。因为 advisor 的 `after` 是在整个 tool-loop 结束、拿到最终答复之后才执行的。这样做的好处：
   - ChatMemory 只保留对话语义；
   - 下一轮对话不会把几十 KB 的 shell 输出带回去，省 token；
   - 模型记得「你之前让我查了 /tmp 下的文件」，但不记得具体 stdout——需要的话再调一次工具即可。

### 2.4 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`

Spring AI 1.1 把 ChatMemory 拆成了两层：

```
ChatMemory  ← 接口，负责「窗口策略 / 截断策略」
    │
    └── MessageWindowChatMemory  ← 保留最近 N 条消息
            │
            └── ChatMemoryRepository   ← 接口，负责「持久化」
                    │
                    └── InMemoryChatMemoryRepository   ← 进程内 Map<String, List<Message>>
```

本项目配置的是 `maxMessages(40)` + 内存存储；如果要换 Redis / JDBC 持久化，只替换 repository 即可，上层 `MessageWindowChatMemory` 不用动。

### 2.5 `ChatModelCallAdvisor` — 责任链的终点

```java
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    Prompt prompt = request.prompt();
    ChatResponse response = chatModel.call(prompt);   // ← 进入 OpenAiChatModel
    return new ChatClientResponse(...);
}
```

它不再调用 `chain.nextCall`，而是直接把 prompt 交给 `ChatModel`（本项目是 `OpenAiChatModel`）。

---

## 3. 模型调用 + 工具执行循环（重点）

这一段是 **Spring AI 1.1 的最大变化之一**：tool-calling 的循环**不在 ChatClient / Advisor 层**，而是**在 ChatModel 内部**做的。

### 3.1 `OpenAiChatModel.call()` 伪代码

```java
public ChatResponse call(Prompt prompt) {
    Prompt requestPrompt = buildRequestPrompt(prompt);   // 合并 defaultOptions
    return internalCall(requestPrompt, null);
}

public ChatResponse internalCall(Prompt prompt, ChatResponse previousResponse) {
    // 1) 把 prompt.messages 转成 OpenAI 格式，tools 转成 JSON Schema
    ChatCompletionRequest req = createRequest(prompt, false);

    // 2) 发 HTTP
    ResponseEntity<ChatCompletion> http = retryTemplate.execute(...)
        .map(r -> openAiApi.chatCompletionEntity(req, headers));

    ChatCompletion completion = http.getBody();
    ChatResponse response = toChatResponse(completion, previousResponse);

    // 3) 判断：assistant 里有没有 tool_calls？
    if (toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
        //    有！交给 ToolCallingManager 执行
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);

        //    把 tool 结果拼成新消息历史 → 递归
        if (result.returnDirect()) {
            return buildFinalResponseFromToolResult(result);   // 工具说「不用再问模型」
        }
        return internalCall(
            new Prompt(result.conversationHistory(), prompt.getOptions()),
            response
        );
    }

    return response;   // 没 tool_calls → 返回
}
```

**关键**：`internalCall` 会 **递归自己** 直到模型不再请求工具。ChatClient 和 Advisor 只看到最后一轮的返回。

### 3.2 `DefaultToolCallingManager.executeToolCalls()` 做了什么

```java
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
    AssistantMessage assistant = response.getResult().getOutput();
    List<ToolCall> toolCalls = assistant.getToolCalls();       // 模型请求的工具调用

    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
    for (ToolCall call : toolCalls) {
        // 1) 按 name 从注册表里找 callback（实现：StaticToolCallbackResolver / SpringBeanToolCallbackResolver）
        ToolCallback cb = resolver.resolve(call.name());

        // 2) 反序列化参数 JSON → 调用
        String result = cb.call(call.arguments(), toolContext);

        // 3) 收集结果
        responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
    }

    // 4) 拼历史：原 messages + AssistantMessage(含 tool_calls) + ToolResponseMessage
    List<Message> history = buildConversationHistoryAfterToolExecution(
        prompt.getInstructions(), assistant, new ToolResponseMessage(responses)
    );
    return new ToolExecutionResult(history, ...);
}
```

### 3.3 `MethodToolCallback.call()` 真正调方法

```java
public String call(String argumentsJson, ToolContext context) {
    // 1) JSON → Map<String, Object>
    Map<String, Object> args = extractToolArguments(argumentsJson);

    // 2) 按方法签名把 Map 里的值转成实参数组
    Object[] methodArgs = buildMethodArguments(args, context);

    // 3) 反射调用 → ShellExecSkill.execute(command, cwd)
    Object result = callMethod(methodArgs);

    // 4) 结果转字符串（默认直接 toString / JSON 序列化）
    return resultConverter.convert(result, method.getGenericReturnType());
}
```

所以 `ShellExecSkill.execute()` 的 `return buildString { ... }` 拿到的那个大字符串，会作为 `ToolResponseMessage` 喂回给模型。

---

## 4. 一次完整交互的时序（带 shell 工具调用）

以 `jAgent> 当前目录下有哪些 .kt 文件?` 为例：

```
1. CliRunner.readLine() 拿到 input
2. AgentService.chat(input)
       ├─ chatClient.prompt()
       │    .user(input)
       │    .advisors{ param(CONVERSATION_ID, "cli-session") }
       │    .call()
       │       ─────────────────────────── 进入 AdvisorChain ───────────────────────────
       │
       ├─ MessageChatMemoryAdvisor.before
       │    ├─ 读 ChatMemory["cli-session"] → []（首次）
       │    └─ 新 prompt.messages = [System, User("当前目录下有哪些 .kt 文件?")]
       │
       ├─ ChatModelCallAdvisor → OpenAiChatModel.call
       │    ├─── HTTP 1 ──→ POST /v1/chat/completions
       │    │     body: { messages: [...], tools: [execute,readFile,listDir,...] }
       │    │     ←── assistant: { tool_calls: [{ name:"execute", args:{command:"ls *.kt"} }] }
       │    │
       │    ├─ ToolCallingManager.executeToolCalls
       │    │    ├─ resolver.resolve("execute") → MethodToolCallback(ShellExecSkill.execute)
       │    │    ├─ cb.call('{"command":"ls *.kt"}')
       │    │    │    └─ ShellExecSkill.execute(command="ls *.kt", cwd=null)
       │    │    │         └─ ProcessBuilder("/bin/sh","-c","ls *.kt").start()
       │    │    │         └─ return "$ ls *.kt (exit=0) --- stdout --- Main.kt\n..."
       │    │    └─ 拼 history = [..., AssistantMsg(tool_calls), ToolResponseMsg(result)]
       │    │
       │    ├─── HTTP 2 ──→ POST /v1/chat/completions
       │    │     body: { messages: [上面的 history] }
       │    │     ←── assistant: { content: "当前目录下有 Main.kt、App.kt 两个 .kt 文件。" }
       │    │     （finish_reason=stop，不再 tool_calls）
       │    │
       │    └─ 返回 ChatResponse
       │
       ├─ MessageChatMemoryAdvisor.after
       │    └─ ChatMemory.add("cli-session",
       │          [ UserMessage("当前目录..."), AssistantMessage("当前目录下有 Main.kt、App.kt") ])
       │
       └─ .content() → "当前目录下有 Main.kt、App.kt 两个 .kt 文件。"
3. CliRunner 把字符串 println 出来
```

**下一轮**用户输入 `这两个文件都是 Kotlin 写的吗?`：
- `MessageChatMemoryAdvisor.before` 读 ChatMemory 拿到上面那 2 条消息
- 拼进 prompt 送给模型
- 模型基于历史推理答复
- advisor 再把新的 user+assistant 追加进 memory（已满 40 条时滑动淘汰最早的）

---

## 5. 常见问题对应链路位置

| 现象 | 可能原因 | 位置 |
|---|---|---|
| `conversationId cannot be null` | 没传 advisor param | `MessageChatMemoryAdvisor.before` |
| 模型不调工具 | 模型本身不支持 function calling / tool schema 描述不清楚 | `MethodToolCallbackProvider` 生成 schema，送给 `OpenAiChatModel.createRequest` |
| 工具被调了但参数是空的 | `@ToolParam` 缺少 `description` 或 JSON 反序列化失败 | `MethodToolCallback.extractToolArguments` |
| 多轮对话没记忆 | advisor 没注册 / conversationId 每次都变 | `ChatMemory.add` / `get` |
| 日志看不到 tool_calls | 默认 `logging.level.org.springframework.ai=WARN` | 调成 `DEBUG` 能看到 `DefaultToolCallingManager` 和 `OpenAiChatModel` 的详细日志 |
| 死循环调工具 | 模型一直返回 tool_calls（prompt 引导不够） | `OpenAiChatModel.internalCall` 的递归；Spring AI 没有硬上限，靠 `toolExecutionEligibilityPredicate` 控制 |

---

## 5.1 调试：打印发给模型的 HTTP 原始请求/响应

项目内置 `HttpDebugConfig`：通过 `RestClientCustomizer` 在 Spring AI 使用的
`RestClient` 上挂一个 `ClientHttpRequestInterceptor`，把每次发到模型的
**完整 HTTP 请求（method/URL/headers/body）和响应（status/headers/body）**
按 INFO 级别打印出来，JSON body 会自动 pretty print，`Authorization` 会脱敏。

### 配置

```yaml
jagent:
  debug:
    log-http: true                    # 是否开启拦截器（默认 true）
    log-http-body-max: 8192           # body 截断长度
logging:
  level:
    jagent.http: INFO                 # 拦截器专用的 logger
    org.springframework.ai: WARN      # 其它 Spring AI 日志全关
```

### 输出样例

```
==================== HTTP #1 → REQUEST ====================
POST https://opencode.ai/zen/v1/chat/completions
headers: {Content-Type=application/json, Authorization=Bearer sk-xxx…abcd}
body:
{
  "model" : "minimax-m2.5-free",
  "messages" : [
    { "role" : "system", "content" : "你是 jAgent..." },
    { "role" : "user",   "content" : "当前目录下有哪些 .kt 文件?" }
  ],
  "tools" : [
    { "type" : "function", "function" : { "name" : "execute", "parameters" : {...} } },
    ...
  ]
}
============================================================

==================== HTTP ← RESPONSE 200 ====================
headers: {Content-Type=application/json, ...}
body:
{
  "choices" : [ {
    "message" : {
      "role" : "assistant",
      "tool_calls" : [ { "id": "...", "function": {"name":"execute","arguments":"{\"command\":\"ls *.kt\"}"} } ]
    }
  } ]
}
==============================================================
```

由于 `OpenAiChatModel.internalCall` 在有 `tool_calls` 时会递归调用自己，你会按顺序
看到多次 `REQUEST/RESPONSE`，可以完整追溯一次问答里所有的模型交互。

### 临时开/关

```bash
JAGENT_LOG_HTTP=false ./gradlew run      # 关闭
JAGENT_LOG_HTTP=true  ./gradlew run      # 打开（默认）
```

### 原理

- `RestClientCustomizer` 是 Spring Boot 的标准扩展点，所有自动装配出的 `RestClient.Builder`
  都会被它加工一遍。Spring AI 的 `OpenAiApi` 在构造时注入 `ObjectProvider<RestClient.Builder>`
  就拿到了带拦截器的 builder。
- `BufferingClientHttpRequestFactory` 把响应包一层，使 body 可以被 interceptor 读完
  之后上层仍能再读一次，否则 `OpenAiChatModel` 解析时会拿到空流。
- 拦截器只影响 Spring AI 的 HTTP 调用，不影响进程里其它 RestClient（如有）。

---

## 6. 可扩展方向

- **换模型**：只改 `application.yml` 的 `base-url` / `api-key` / `model`，其它层完全不变。
- **换持久化**：实现 `ChatMemoryRepository` 接入 Redis / Postgres，替换 `MessageWindowChatMemory.builder().chatMemoryRepository(...)`。
- **自定义 advisor**：实现 `CallAdvisor`（如敏感词过滤、prompt 审计、RAG 注入），用 `.defaultAdvisors(...)` 注册，自动排到链里。
- **工具执行前后拦截**：实现 `ToolCallingObservationConvention` 或自定义 `ToolCallbackResolver`，包一层 `ToolCallback` 代理。
- **每个用户独立记忆**：把 `conversationId` 改成和用户会话 ID 绑定（例如 HTTP 场景取 sessionId），ChatMemory 自动隔离。

---

## 6.1 文件式 Skill 的渐进加载机制

仿 Claude Code 设计：**只对外暴露一个元工具 `skill`**，它的 description 里直接嵌入
所有 skill 的 name + 一句话描述。模型从 tools 数组里就能看到有什么能力，需要时再
调 `skill(name)` 获取具体指令——system prompt 与 skill 数量完全解耦。

### 数据流

```
┌──────────────────────────────────────────────────────────────────┐
│ 启动期                                                            │
│   SkillRegistry.scan()                                           │
│     │ 遍历 jagent.skills.paths                                   │
│     │ 解析每个 SKILL.md 的 YAML frontmatter（name/description）   │
│     └─> skills: Map<String, SkillDescriptor>                     │
│                                                                   │
│   AgentService 装配                                               │
│     │ ChatClient.defaultToolCallbacks(                           │
│     │     shell_execute 的 MethodToolCallback,                   │
│     │     SkillTool (自定义 ToolCallback)                        │
│     │ )                                                           │
│     │ system prompt 固定不变，不拼 skill 列表                     │
│     └─>                                                           │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ 每次请求                                                          │
│                                                                   │
│  OpenAiChatModel.createRequest()                                 │
│     └─ 对每个 ToolCallback 调 getToolDefinition()                │
│         ├─ shell_execute: 返回固定 definition                    │
│         └─ skill: 返回一个现算 definition                        │
│                    ├─ name = "skill"                              │
│                    ├─ description = "...Available skills:        │
│                    │                  - git-helper: ...           │
│                    │                  - sys-inspect: ..."         │
│                    │  (每次请求都用最新的 registry 内容构造)      │
│                    └─ inputSchema = {"name": string, required}   │
│                                                                   │
│  HTTP POST → opencode zen                                         │
│     body.tools = [shell_execute 定义, skill 定义（含 catalog）]  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ 模型执行                                                          │
│                                                                   │
│  User: "总结一下这台机器的负载"                                   │
│    ↓ 模型从 `skill` tool 的 description 看到 sys-inspect 匹配     │
│  Model: tool_call skill({"name":"sys-inspect"})                  │
│    ↓                                                              │
│  SkillTool.call('{"name":"sys-inspect"}')                        │
│    └─> SkillRegistry.loadBody("sys-inspect")                     │
│        ├─ 读 SKILL.md 全文                                        │
│        ├─ 去掉 frontmatter                                        │
│        ├─ 附上 skill 目录清单（limit 50）                         │
│        └─> 返回给模型                                             │
│    ↓                                                              │
│  Model: tool_call shell_execute("bash ./skills/sys-inspect/...") │
│    ↓                                                              │
│  Model: 把 stdout 整理成结构化回答                                │
└──────────────────────────────────────────────────────────────────┘
```

### 为什么把 catalog 放在 tool description 而不是 system prompt

1. **system prompt 不可变，提示缓存（prompt caching）命中率高**：OpenAI / Anthropic 的
   prompt caching 对前缀 token 敏感，skill 新增/删除不会让 system prompt 变，省钱。
2. **工具说明天然和 tools 数组绑定**：tools 是每次请求必带的载荷，在它里面 "夹带"
   catalog 不增加额外字段，结构内聚。
3. **单一入口收敛**：不用区分 `list_skills` / `load_skill` 两个 tool，模型只要记住
   `skill(name)` 一个签名；LLM 对工具数量越少越容易用对。
4. **Claude Code 的选择**：泄露源码显示 Claude Code 的 `skill` tool 正是这种做法。

### 动态 description 怎么实现的

`@Tool` 注解的 description 是编译期常量，没法动态。所以 `SkillTool` **不用 `@Tool` 注解**，
而是 **直接实现 `org.springframework.ai.tool.ToolCallback` 接口**：

```kotlin
class SkillTool(private val registry: SkillRegistry) : ToolCallback {
    override fun getToolDefinition(): ToolDefinition =
        ToolDefinition.builder()
            .name("skill")
            .description(buildDescription())   // ← 每次调用都现算
            .inputSchema(INPUT_SCHEMA)
            .build()

    override fun call(toolInput: String): String {
        val name = jackson.readTree(toolInput)["name"].asText()
        return registry.loadBody(name)
    }
}
```

`OpenAiChatModel.createRequest()` 在每次 HTTP 调用前都会对 `ToolCallback` 数组里的每个
对象调 `getToolDefinition()` 拿描述塞进请求体。我们在这个 hook 点注入动态内容。

### 关键代码位置

| 组件 | 职责 |
|---|---|
| `skill/SkillRegistry.kt` | 扫描目录、解析 frontmatter、按需读正文；`reload()` 重扫 |
| `skill/SkillTool.kt` | 实现 `ToolCallback`；`getToolDefinition()` 动态拼 catalog；`call()` 派发到 `registry.loadBody()` |
| `skill/ShellExecSkill.kt` | `shell_execute`，手写 `ToolCallback`（与 `SkillTool` 结构一致）|
| `agent/AgentService.kt` | 两个 `ToolCallback` 直接组合成 `Array<ToolCallback>` 注入 ChatClient；system prompt 保持恒定；全程无注解扫描 / 反射 |

### Skill 文件格式

```
skills/<name>/
├── SKILL.md          必需。frontmatter + markdown 正文
├── scripts/          可选。模型用 shell_execute 跑
└── resources/        可选。模型用 shell_execute cat 读
```

`SKILL.md` frontmatter 只要求两个扁平 key，避免引 yaml 解析库：

```yaml
---
name: my-skill              # 唯一名，缺省时取目录名
description: 一句话描述      # 会被写进 system prompt，模型靠它选 skill
---
```

### 配置

```yaml
jagent:
  skills:
    paths: ./skills          # 多路径用逗号分隔，后出现的覆盖前者
```

---

## 7. 版本备注

- Spring AI: `1.1.6`（见 `build.gradle.kts` 的 `springAiVersion`）
- Spring Boot: `3.4.1`
- 引用的类路径（若版本升级需复核）：
  - `org.springframework.ai.chat.client.ChatClient`
  - `org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor`
  - `org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor`
  - `org.springframework.ai.chat.memory.ChatMemory` / `MessageWindowChatMemory` / `InMemoryChatMemoryRepository`
  - `org.springframework.ai.model.tool.DefaultToolCallingManager`
  - `org.springframework.ai.tool.method.MethodToolCallback` / `MethodToolCallbackProvider`
  - `org.springframework.ai.openai.OpenAiChatModel`
