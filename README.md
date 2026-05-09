# jAgent

一个用 **Kotlin + Spring AI** 实现的命令行 Agent，支持类似 Claude Code 的 **文件式 Skill + 渐进加载**。

## 文档

- [docs/architecture.md](docs/architecture.md) — 从 ChatClient 到模型再到工具执行的完整调用链，含 Skill 渐进加载的实现细节

## 特性

- Gradle Kotlin DSL (`build.gradle.kts`) + Spring Boot 3 + Spring AI 1.1.6
- **文件式 Skill**：一个目录放一个 `SKILL.md` 就能扩展 Agent 能力，**无需改代码、无需重启**
- **渐进式加载（仿 Claude Code 的 `skill` 单元工具设计）**：只有一个 `skill` 工具，它的
  description 里嵌入 skill 清单（name + description），模型从 tools 数组就能看到有什么能力；
  需要时调 `skill(name)` 才返回该 skill 的 SKILL.md 正文。**system prompt 保持恒定**，
  提示缓存更稳、token 更省
- 内置两个工具：
  - `shell_execute`：在本机执行 CLI 命令（带白名单和超时保护），也用来跑 Skill 里的脚本
  - `skill(name)`：加载某个 skill 的 playbook；可用 skill 列表由 tool description 动态提供
- HTTP 级请求/响应原文打印（可选）
- 多轮对话（`MessageWindowChatMemory`）
- 交互式 REPL + 单次命令两种模式

## 目录结构

Skill 扫描路径按顺序来自 `jagent.skills.paths`（逗号分隔，路径开头的 `~` 会展开为
`$HOME`）。默认值是 `~/.agents/skills,./skills`：

- `~/.agents/skills/` — **用户级**：跨项目复用的个人 skill（类似 `~/.gitconfig`）
- `./skills/` — **项目级**：随仓库走的 skill，同名时覆盖用户级（类似仓库内 `.git/config`）

```
~/.agents/skills/                        # ← 用户维度，跨项目共享
├── my-note/
│   └── SKILL.md
└── ...

jAgent/
├── build.gradle.kts
├── settings.gradle.kts
├── skills/                              # ← 项目维度，随仓库提交
│   ├── git-helper/
│   │   └── SKILL.md
│   └── sys-inspect/
│       ├── SKILL.md
│       └── scripts/collect.sh
├── src/main/kotlin/com/example/jagent/
│   ├── JAgentApplication.kt
│   ├── agent/AgentService.kt            # ChatClient 装配，构造 skill catalog 注入 system prompt
│   ├── cli/CliRunner.kt                 # 交互式 CLI
│   ├── debug/HttpDebugConfig.kt         # 打印发给模型的 HTTP 原文
│   └── skill/
│       ├── ShellExecSkill.kt            # built-in: shell_execute（手写 ToolCallback）
│       ├── SkillRegistry.kt             # 扫描 ./skills 目录、解析 SKILL.md frontmatter
│       └── SkillTool.kt                 # built-in: skill(name)（手写 ToolCallback，动态 description）
└── src/main/resources/application.yml
```

## 快速开始

### 1. 配置模型

默认指向 [opencode zen](https://opencode.ai/docs/zen/)（含免费模型）：

```bash
export OPENCODE_ZEN_API_KEY="oc-xxxx"
export OPENAI_MODEL="minimax-m2.5-free"       # 可选：big-pickle / ling-2.6-flash / ...
```

切到其它 OpenAI 兼容端点：

```bash
export OPENAI_API_KEY="sk-xxx"
export OPENAI_BASE_URL="https://api.deepseek.com"
export OPENAI_MODEL="deepseek-chat"
```

> `base-url` 只写到域名，Spring AI 自动拼 `/v1/chat/completions`。

### 2. 生成 Gradle Wrapper（首次）

```bash
gradle wrapper --gradle-version 8.11.1
```

### 3. 运行

```bash
./gradlew run                                           # 交互式
./gradlew run --args="总结一下这台机器的负载情况"       # 单次
```

交互式命令：

```
jAgent> /skills      # 列出当前安装的 skill
jAgent> /reload      # 重新扫描 skill 目录（新增/修改 skill 后）
jAgent> /reset       # 清空当前会话历史
jAgent> /help        # 帮助
jAgent> /exit        # 退出
```

## 编写一个 Skill

一个 **Skill = 一个目录 + 一个 SKILL.md**。

```
./skills/my-skill/
├── SKILL.md            # 必需，头部 YAML + 指令正文
├── scripts/            # 可选，模型通过 shell_execute 运行
└── resources/          # 可选，模型通过 shell_execute cat 查看
```

**`SKILL.md` 示例**：

````markdown
---
name: my-skill
description: 一句话描述这个 skill 做什么，模型靠它判断要不要加载
---

# my-skill

在这里写详细指令。例如：

## 步骤
1. 先用 `shell_execute("git status -sb")` 看当前状态
2. 再跑本 skill 的脚本：
   ```
   shell_execute("bash ./skills/my-skill/scripts/run.sh")
   ```
3. 按脚本输出整理给用户
````

### 渐进加载是如何发生的

1. **启动**：`SkillRegistry` 扫描 `./skills/*/SKILL.md`，**只解析 frontmatter**。不改 system prompt。
2. **每次请求**：`SkillTool.getToolDefinition()` 被 Spring AI 调用，此时现算一份
   description，内容形如：

   ```
   Loads the full instructions for a domain-specific capability ("skill") by name.
   ...
   Available skills (name — description):
     - git-helper: 查看 git 仓库的状态、历史、分支、diff，...
     - sys-inspect: 收集本机基本信息——OS、CPU、内存、...
   ...
   ```

   这段文字随着 `tools[]` 数组一起发给模型。模型从 tool description 就"看见"了所有 skill。
3. **匹配则加载**：模型调 `skill("git-helper")`，`SkillTool.call()` 从 `SkillRegistry`
   取出 `SKILL.md` 正文 + 目录清单返回给模型。
4. **执行**：模型按正文指令调 `shell_execute` 跑脚本、读文件。
5. **热更新**：新加 skill 后 `/reload`（或重启）；下一次请求 tool description 就会自动
   带上新 skill。**无需改代码、无需改 system prompt**。

#### 为什么放在 tool description 而不是 system prompt？

- **system prompt 保持恒定**：skill 列表变化不会让 system prompt 变，提示缓存
  （prompt caching）命中率更高，省费用。
- **工具和说明天然绑定**：tools 数组本来就是每次请求必带的，把 catalog 塞进去不需要额外字段。
- **单一入口更收敛**：模型只要记住 `skill(name)` 一个工具，不用区分 list vs load。

### 添加新 Skill 无需重启（热加载）

```bash
mkdir -p ./skills/hello/
cat > ./skills/hello/SKILL.md <<'EOF'
---
name: hello
description: 打个招呼的示例
---
# hello
直接回答 "你好 + 当前时间"，用 shell_execute("date") 获取时间。
EOF
```

在 REPL 里执行 `/reload`，然后问 `hello` 试试。

## Shell 执行安全

```yaml
jagent:
  shell:
    allow-all: false                 # true 关闭白名单（危险）
    allow-list: ls,pwd,cat,git,bash,...
    timeout-seconds: 30
    working-dir: ${user.home}
```

Skill 里要跑脚本时，确保 `bash` 在白名单里，或设置 `allow-all: true`。

## 调试：看模型收到/返回了什么

```yaml
jagent:
  debug:
    log-http: true     # 启动时挂 RestClient interceptor，INFO 打印 HTTP 原文
```

或环境变量 `JAGENT_LOG_HTTP=true ./gradlew run`。

## 注意

- Skill 的 frontmatter 要贴在文件最开头，`---` 包起来。
- 同名 skill 后扫到的会覆盖先扫到的。
- 当前仓库的免费模型对 Tool Calling 支持程度不一，效果不稳时换个模型。
