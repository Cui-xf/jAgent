package com.example.jagent.skill

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 对应目录里的一个 skill。
 *
 * @param name        唯一名；取自 SKILL.md frontmatter 的 name，缺省为目录名
 * @param description 一句话描述；加载时作为 system prompt 里的摘要给模型看
 * @param dir         skill 根目录
 * @param skillMd     SKILL.md 的绝对路径
 */
data class SkillDescriptor(
    val name: String,
    val description: String,
    val dir: Path,
    val skillMd: Path,
)

/**
 * 扫描 jagent.skills.paths 下所有 skill 目录，解析 SKILL.md 的 YAML frontmatter。
 *
 * 扫描规则：
 *  - 每条路径下，第一层子目录若包含 SKILL.md，就视为一个 skill
 *  - 路径中的 `~` 会被展开为用户 HOME
 *  - 多条路径下同名时，后出现的覆盖前者（类似 gitconfig 的 system → global → local：
 *    默认先扫用户级 `~/.agents/skills`，再扫项目级 `./skills`，项目级覆盖用户级）
 *
 * 渐进加载策略：
 *  - 启动期只解析 frontmatter，不读正文
 *  - 正文在模型调用 skill(name) 时按需读取
 */
@Component
class SkillRegistry(
    @Value("\${jagent.skills.paths:~/.agents/skills,./skills}") private val pathsRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val skills = ConcurrentHashMap<String, SkillDescriptor>()

    init {
        scan()
    }

    fun all(): List<SkillDescriptor> = skills.values.sortedBy { it.name }

    fun get(name: String): SkillDescriptor? = skills[name]

    /**
     * 按需读取 SKILL.md 完整内容（含 frontmatter 以下的正文部分）。
     * 失败时返回 "[error] ..."，交给工具层原样返回给模型。
     */
    fun loadBody(name: String): String {
        val s = skills[name] ?: return "[error] skill not found: $name"
        return runCatching {
            val raw = s.skillMd.readText()
            // 去掉 frontmatter，把正文连同目录结构一起丢给模型
            val body = stripFrontmatter(raw)
            buildString {
                appendLine("# Skill: ${s.name}")
                appendLine()
                appendLine("Skill directory: `${s.dir}`")
                appendLine()
                val inventory = listSkillFiles(s.dir)
                if (inventory.isNotBlank()) {
                    appendLine("Files in this skill (use `shell_execute` to read/run them):")
                    appendLine("```")
                    appendLine(inventory)
                    appendLine("```")
                    appendLine()
                }
                append(body.trim())
            }
        }.getOrElse { "[error] load skill failed: ${it.message}" }
    }

    /** 重新扫描磁盘，供手动 reload 使用。 */
    fun reload(): Int {
        skills.clear()
        return scan()
    }

    private fun scan(): Int {
        val paths = pathsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var count = 0
        for (p in paths) {
            val root = expandHome(p).toAbsolutePath().normalize()
            if (!root.isDirectory()) {
                log.info("skill 目录不存在，跳过：{}", root)
                continue
            }
            log.info("扫描 skill 目录：{}", root)
            Files.list(root).use { s ->
                s.filter { it.isDirectory() }.forEach { dir ->
                    val md = dir.resolve("SKILL.md")
                    if (!md.isRegularFile()) return@forEach
                    runCatching {
                        val fm = parseFrontmatter(md.readText())
                        val name = fm["name"] ?: dir.name
                        val desc = fm["description"] ?: "(no description)"
                        val prev = skills.put(name, SkillDescriptor(name, desc, dir, md))
                        if (prev != null) {
                            log.info("skill \"{}\" 被覆盖：{} → {}", name, prev.dir, dir)
                        }
                        count++
                    }.onFailure { log.warn("解析 skill {} 失败：{}", dir, it.message) }
                }
            }
        }
        log.info("已加载 {} 个 skill：{}", skills.size, skills.keys.sorted())
        return count
    }

    /**
     * 把路径前导的 `~` 展开为用户 HOME——Path.of 不会处理 `~`。
     * 支持 `~`、`~/foo`，不支持 `~user/foo`（那是 shell 专属语法）。
     */
    private fun expandHome(raw: String): Path {
        if (raw == "~") return Path.of(System.getProperty("user.home"))
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2))
        }
        return Path.of(raw)
    }

    /**
     * 解析 YAML frontmatter：
     *
     * ```
     * ---
     * name: foo
     * description: bar
     * ---
     * markdown 正文
     * ```
     *
     * 这里只识别扁平的 `key: value`，不依赖 yaml 库，避免多一个依赖。
     */
    private fun parseFrontmatter(text: String): Map<String, String> {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != "---") return emptyMap()
        val map = mutableMapOf<String, String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trim() == "---") return map
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim().trim('"', '\'')
            map[k] = v
        }
        return map  // 没闭合的 frontmatter 也尽量返回
    }

    private fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text
        val secondDelim = text.indexOf("\n---", startIndex = 3)
        if (secondDelim < 0) return text
        val after = text.indexOf('\n', secondDelim + 1)
        return if (after < 0) "" else text.substring(after + 1)
    }

    /** 简单地 ls -R，但跳过 .git 等隐藏目录，截断到 50 条。 */
    private fun listSkillFiles(dir: Path): String {
        return Files.walk(dir).use { s ->
            s.filter { it != dir }
                .filter { !it.fileName.toString().startsWith(".") }
                .map { dir.relativize(it).toString() + if (it.isDirectory()) "/" else "" }
                .sorted()
                .limit(50)
                .toList()
                .joinToString("\n")
        }
    }
}
