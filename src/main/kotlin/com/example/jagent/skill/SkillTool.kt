package com.example.jagent.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.stereotype.Component

/**
 * 单元工具版的 Skill 入口——仿 Claude Code 的 `skill` tool：
 *
 *  - 只对模型暴露一个工具：`skill`
 *  - 所有 skill 的元信息（name + description）作为 **tool description 的一部分** 直接
 *    写在 getToolDefinition() 返回里，每次请求都会自动拼到发给模型的 `tools[]` 数组里，
 *    不占 system prompt 的位置
 *  - tool 用法说明也直接写在 description 中——模型根据 description 就知道什么时候调、
 *    调用后拿到 SKILL.md 正文后该怎么继续（去调 shell_execute 跑脚本）
 *
 * 渐进加载：description 里只有 name/description 列表；正文在调用 skill(name) 后才返回。
 *
 * 动态 description 的关键：不能用 @Tool 注解（注解只能填编译期常量），必须直接实现
 * ToolCallback 接口——getToolDefinition() 每次返回一个根据当前 registry 现算的定义。
 */
@Component
class SkillTool(private val registry: SkillRegistry) : ToolCallback {

    private val json: ObjectMapper = jacksonObjectMapper()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition.builder()
            .name("skill")
            .description(buildDescription())
            .inputSchema(INPUT_SCHEMA)
            .build()
    }

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val name = runCatching {
            val node = json.readTree(toolInput)
            node["name"]?.asText()?.trim().orEmpty()
        }.getOrElse {
            return "[error] invalid arguments json: ${it.message}"
        }

        if (name.isEmpty()) {
            return "[error] missing required argument \"name\"; " +
                "available skills: ${registry.all().joinToString(", ") { it.name }}"
        }

        return registry.loadBody(name)
    }

    /**
     * 每次请求都现算：模型每次对话时 description 都是 skill 目录的最新快照。
     */
    private fun buildDescription(): String {
        val all = registry.all()
        val catalog = if (all.isEmpty()) {
            "  (no skills installed)"
        } else {
            all.joinToString("\n") { "  - ${it.name}: ${it.description}" }
        }
        return """
Loads the full instructions for a domain-specific capability ("skill") by name.

Call this tool whenever the user's request matches one of the skills listed below —
it returns that skill's detailed playbook (steps, scripts to run, resources to read).
After you receive the playbook, follow its instructions, typically by calling
`shell_execute` to run scripts inside the skill directory or to cat its resource files.

Available skills (name — description):
$catalog

Usage:
  skill({ "name": "<one of the names above>" })

Tips:
  - The name argument must exactly match a name from the list above.
  - If no skill fits, don't call this tool; just use `shell_execute` and answer directly.
  - A skill's playbook may reference files inside its own directory — use
    `shell_execute("cat ./skills/<name>/...")` to read them when needed.
""".trimIndent()
    }

    companion object {
        /** 输入 schema：固定为一个字符串字段 name。 */
        private const val INPUT_SCHEMA = """
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "The skill name to load. Must match one of the names listed in this tool's description."
    }
  },
  "required": ["name"]
}
""";
    }
}
