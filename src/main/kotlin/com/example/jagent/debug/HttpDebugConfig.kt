package com.example.jagent.debug

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpRequest
import org.springframework.http.MediaType
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * 在 Spring AI 使用的 RestClient 上挂一个拦截器，
 * 用 INFO 级别打印发给模型的原始 HTTP 请求 / 响应（含 JSON body）。
 *
 * 只有当 jagent.debug.log-http=true 时才装配。
 */
@Configuration
class HttpDebugConfig(
    @Value("\${jagent.debug.log-http:true}") private val enabled: Boolean,
    @Value("\${jagent.debug.log-http-body-max:8192}") private val bodyMax: Int,
) {
    private val log = LoggerFactory.getLogger("jagent.http")
    private val seq = AtomicLong(0)
    private val prettyJson: ObjectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    @Bean
    fun aiHttpLoggingCustomizer(): RestClientCustomizer {
        if (!enabled) return RestClientCustomizer { /* no-op */ }

        val interceptor = ClientHttpRequestInterceptor { request, body, execution ->
            logRequest(request, body)
            val resp = execution.execute(request, body)
            logResponse(request, resp)
            resp
        }

        return RestClientCustomizer { builder ->
            // BufferingClientHttpRequestFactory 让响应体可重复读取，否则 interceptor 读完 body
            // 后上层拿到的就是空流了。JdkClientHttpRequestFactory 走 JDK 内置 HttpClient。
            builder
                .requestFactory(BufferingClientHttpRequestFactory(JdkClientHttpRequestFactory()))
                .requestInterceptor(interceptor)
        }
    }

    private fun logRequest(request: HttpRequest, body: ByteArray) {
        val id = seq.incrementAndGet()
        val safeHeaders = request.headers.toSingleValueMap().mapValues { (k, v) ->
            if (k.equals("Authorization", ignoreCase = true)) maskAuth(v) else v
        }
        log.info(
            "\n==================== HTTP #{} → REQUEST ====================\n{} {}\nheaders: {}\nbody:\n{}\n============================================================",
            id,
            request.method,
            request.uri,
            safeHeaders,
            formatBody(body, request.headers.contentType),
        )
    }

    private fun logResponse(request: HttpRequest, response: ClientHttpResponse) {
        val bytes = response.body.readAllBytes()
        log.info(
            "\n==================== HTTP ← RESPONSE {} {} ====================\nheaders: {}\nbody:\n{}\n==============================================================",
            response.statusCode.value(),
            request.uri,
            response.headers.toSingleValueMap(),
            formatBody(bytes, response.headers.contentType),
        )
    }

    private fun formatBody(bytes: ByteArray, contentType: MediaType?): String {
        if (bytes.isEmpty()) return "<empty>"
        val truncated = if (bytes.size > bodyMax) {
            String(bytes, 0, bodyMax, StandardCharsets.UTF_8) +
                "\n...[truncated ${bytes.size - bodyMax} bytes]"
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
        // JSON 尝试 pretty print；失败就原样返回
        if (contentType != null && contentType.subtype.contains("json")) {
            return runCatching {
                prettyJson.writeValueAsString(prettyJson.readTree(truncated))
            }.getOrDefault(truncated)
        }
        return truncated
    }

    private fun maskAuth(v: String): String =
        if (v.length <= 12) "***" else v.take(10) + "…" + v.takeLast(4)
}
