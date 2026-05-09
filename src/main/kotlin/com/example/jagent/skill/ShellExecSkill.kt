package com.example.jagent.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 让模型在本机执行 shell 命令的工具。直接实现 ToolCallback，避免走 @Tool 注解扫描。
 *
 * 出于安全考虑：
 *  - 通过 jagent.shell.allow-all / jagent.shell.allow-list 控制哪些命令可执行
 *  - 默认超时 30 秒，避免卡死
 *  - 默认工作目录可配
 */
@Component
class ShellExecSkill(
    @Value("\${jagent.shell.allow-all:false}") private val allowAll: Boolean,
    @Value("\${jagent.shell.allow-list:ls,pwd,cat,echo,grep,find,wc,head,tail,git,uname,whoami,date,which,env}")
    private val allowListRaw: String,
    @Value("\${jagent.shell.timeout-seconds:30}") private val timeoutSeconds: Long,
    @Value("\${jagent.shell.working-dir:#{systemProperties['user.home']}}") private val workingDir: String,
) : ToolCallback {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json: ObjectMapper = jacksonObjectMapper()
    private val allowList: Set<String> by lazy {
        allowListRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private val toolDefinition: ToolDefinition = ToolDefinition.builder()
        .name("shell_execute")
        .description(
            "Execute a shell command on this machine (e.g. `ls`, `git status`, " +
                "`cat file.txt`, `bash path/to/script.sh`). Returns stdout, stderr and exit code.",
        )
        .inputSchema(INPUT_SCHEMA)
        .build()

    override fun getToolDefinition(): ToolDefinition = toolDefinition

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val (command, cwd) = runCatching {
            val node = json.readTree(toolInput)
            val cmd = node["command"]?.asText()?.trim().orEmpty()
            val dir = node["cwd"]?.asText()?.takeIf { it.isNotBlank() }
            cmd to dir
        }.getOrElse { return "[error] invalid arguments json: ${it.message}" }

        if (command.isEmpty()) return "[error] missing required argument \"command\""

        val head = command.split(Regex("\\s+")).first()
        if (!allowAll && head !in allowList) {
            return "[blocked] 命令 '$head' 未在白名单中。当前白名单：$allowList；" +
                "如需放开，请设置 jagent.shell.allow-all=true 或把命令加入 jagent.shell.allow-list。"
        }

        log.info("exec: {}", command)
        val dir = File(cwd ?: workingDir)
        val proc = ProcessBuilder("/bin/sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(false)
            .start()

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return "[timeout] 命令执行超过 ${timeoutSeconds}s 被强制终止：$command"
        }

        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit = proc.exitValue()

        return buildString {
            appendLine("$ $command  (cwd=${dir.absolutePath}, exit=$exit)")
            if (stdout.isNotBlank()) {
                appendLine("--- stdout ---")
                append(stdout.take(8000))
                if (stdout.length > 8000) appendLine("\n...[truncated ${stdout.length - 8000} chars]")
            }
            if (stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                append(stderr.take(2000))
            }
        }
    }

    companion object {
        private const val INPUT_SCHEMA = """
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "The full shell command to execute, e.g. `ls -la /tmp`"
    },
    "cwd": {
      "type": "string",
      "description": "Optional absolute path to use as working directory. Defaults to the configured working dir."
    }
  },
  "required": ["command"]
}
"""
    }
}
