package com.example.jagent.cli

import com.example.jagent.agent.AgentService
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class CliRunner(private val agent: AgentService) : CommandLineRunner {

    override fun run(vararg args: String?) {
        printBanner()

        // 支持把命令行参数当成单次提问：./gradlew run --args="帮我看看 /tmp 下有什么文件"
        val inline = args.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        if (inline.isNotBlank()) {
            println("\n> $inline")
            println(agent.chat(inline))
            return
        }

        val br = System.`in`.bufferedReader()
        while (true) {
            print("\njAgent> ")
            System.out.flush()
            val line = br.readLine() ?: break
            val input = line.trim()
            when {
                input.isEmpty() -> continue
                input in setOf("/exit", "/quit", ":q") -> {
                    println("bye.")
                    return
                }

                input == "/skills" -> {
                    val list = agent.listSkills()
                    if (list.isEmpty()) {
                        println("(暂无 skill，往 ~/.agents/skills/<name>/ 或 ./skills/<name>/ 添加 SKILL.md 即可)")
                    } else {
                        println("已安装的 Skill：")
                        list.forEach { println("  - ${it.name}: ${it.description}  [${it.dir}]") }
                    }
                }

                input == "/reload" -> {
                    val n = agent.reloadSkills()
                    println("已重新扫描 skill 目录，共 $n 个。")
                }

                input == "/help" -> printHelp()
                input == "/reset" -> {
                    agent.resetMemory()
                    println("已清空会话历史。")
                }
                else -> runCatching { println(agent.chat(input)) }
                    .onFailure { println("[error] ${it.printStackTrace()}") }
            }
        }
    }

    private fun printBanner() {
        println("=".repeat(60))
        println("  jAgent — 基于 Spring AI 的本地 Agent")
        println("  输入 /help 查看命令，/exit 退出")
        println("=".repeat(60))
    }

    private fun printHelp() {
        println(
            """
            内置命令：
              /help      显示帮助
              /skills    列出已安装的 skill（同时显示来源目录）
              /reload    重新扫描 skill 目录（默认 ~/.agents/skills 和 ./skills）
              /reset     清空当前会话历史
              /exit      退出

            Skill 扫描路径由 jagent.skills.paths 配置，默认是用户级
            (~/.agents/skills) 与项目级 (./skills)，同名时项目级覆盖用户级。

            其它任何输入都会发给模型。模型根据 `skill` tool 的 description 里列出的
            skill 清单判断是否匹配，匹配则调 skill(name) 拿 playbook，再通过
            shell_execute 执行其中的脚本。
            """.trimIndent(),
        )
    }
}
