package com.example.jagent.agent

import com.example.jagent.skill.ShellExecSkill
import com.example.jagent.skill.SkillDescriptor
import com.example.jagent.skill.SkillRegistry
import com.example.jagent.skill.SkillTool
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Service

/**
 * 核心 Agent 服务。
 *
 * Tool 暴露策略（仿 Claude Code）：
 *   - `shell_execute`：通用命令执行入口（跑 skill 脚本、读文件、查 git 等）
 *   - `skill`：唯一的 skill 元工具。description 每次请求都会现算，把 skill 列表
 *     嵌在里面，模型从 tools 数组里就能看到有哪些 skill，无需在 system prompt 里
 *     再重复一份。
 *
 * 好处：
 *   - system prompt 保持精简稳定（不会因为加 skill 而膨胀），提示缓存命中率高
 *   - 单元工具接口更"收敛"：模型只需要知道 skill(name)，不用区分 list_skills / load_skill
 *   - 加 skill 只需要 /reload，下一次请求 tool description 就会自动带上新列表
 */
@Service
class AgentService(
    chatClientBuilder: ChatClient.Builder,
    private val skillRegistry: SkillRegistry,
    shellSkill: ShellExecSkill,
    skillTool: SkillTool,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 两个 ToolCallback 实现直接组合——没有注解扫描、没有反射。 */
    private val toolCallbacks: Array<ToolCallback> = arrayOf(shellSkill, skillTool)

    private val chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(InMemoryChatMemoryRepository())
        .maxMessages(40)
        .build()

    private val chatClient: ChatClient = chatClientBuilder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultToolCallbacks(*toolCallbacks)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build()

    init {
        log.info("tools: {}", toolCallbacks.map { it.toolDefinition.name() })
        log.info("skills (lazy): {}", skillRegistry.all().map { it.name })
    }

    private val defaultConversationId: String = "cli-session"

    fun chat(userInput: String, conversationId: String = defaultConversationId): String {
        return chatClient.prompt()
            .user(userInput)
            .advisors { spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId) }
            .call()
            .content()
            ?: "[empty]"
    }

    fun resetMemory(conversationId: String = defaultConversationId) {
        chatMemory.clear(conversationId)
    }

    fun reloadSkills(): Int = skillRegistry.reload()

    fun listSkills(): List<SkillDescriptor> = skillRegistry.all()

    companion object {
        /**
         * system prompt 只说清楚"你有哪些能力"，具体 skill 目录由 `skill` tool 的
         * description 动态提供——这样 system prompt 保持不变，提示缓存命中率最高。
         */
        private const val SYSTEM_PROMPT = """
你是 jAgent，一个运行在本机的命令行 AI 助手。

你拥有两个工具：
  - shell_execute(command, cwd?)：在本机 shell 执行命令（读文件、列目录、跑脚本、查 git 等）
  - skill(name)：加载某个 skill 的完整指令（playbook）。可用的 skill 清单写在 skill 这个 tool 的 description 里，你可以直接看到。

工作原则：
  1. 用户提出请求后，检查 skill 工具 description 中的 skill 列表，看是否有匹配项。
     - 匹配 → 先调 skill(name) 拿到 playbook，再按 playbook 执行（通常是 shell_execute 跑脚本）。
     - 不匹配 → 直接用 shell_execute 等工具完成。
  2. 高风险操作（删除、覆盖、远程请求）先说明意图，得到用户确认再执行。
  3. 回答简洁，用中文。
"""
    }
}
