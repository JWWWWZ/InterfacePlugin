package com.example.testplugin;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client for any OpenAI-compatible chat-completions API.
 *
 * <p>Compatible with:
 * <ul>
 *   <li>OpenAI – {@code https://api.openai.com}</li>
 *   <li>Azure OpenAI – {@code https://<resource>.openai.azure.com}</li>
 *   <li>Ollama (local) – {@code http://localhost:11434}</li>
 *   <li>Any proxy that speaks {@code POST /v1/chat/completions}</li>
 * </ul>
 *
 * <p><b>This is a blocking call</b> – always invoke it from a background thread
 * (e.g. inside {@link com.intellij.openapi.progress.Task.Backgroundable}).
 */
public final class AiDocClient {

    // ── Defaults ─────────────────────────────────────────────────────────────

    /** Default model name; callers may override via {@link #generateDoc(String, String, String, String)}. */
    public static final String DEFAULT_MODEL = "gpt-4o";

    private static final int CONNECT_TIMEOUT_SEC = 15;
    private static final int REQUEST_TIMEOUT_SEC = 120;

    private static final Gson GSON = new Gson();

    // Single shared client; HttpClient is thread-safe and reuse is recommended.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build();

    private AiDocClient() {
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends the controller JSON (including {@code methodBody} fields) to the
     * AI API and returns the generated Markdown documentation.
     *
     * @param controllerJson JSON string produced by {@link SpringControllerScanner}
     * @param apiBaseUrl     base URL without trailing slash, e.g. {@code https://api.openai.com}
     * @param apiKey         Bearer token; pass {@code ""} for local models without auth
     * @param model          model identifier, e.g. {@code gpt-4o}, {@code llama3}
     * @param onProgress     callback invoked with a human-readable status message at each step;
     *                       runs on the calling (background) thread — update UI via invokeLater
     * @return generated Markdown documentation text
     * @throws Exception on network error, timeout, or non-2xx HTTP status
     */
    public static @NotNull String generateDoc(
            @NotNull String controllerJson,
            @NotNull String apiBaseUrl,
            @NotNull String apiKey,
            @NotNull String model,
            @NotNull java.util.function.Consumer<String> onProgress
    ) throws Exception {
        String url = apiBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        onProgress.accept("[1/4] 正在构建 AI 请求体 (model=" + model + ")…");
        String requestBody = GSON.toJson(Map.of(
                "model", model,
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", buildSystemPrompt()),
                        Map.of("role", "user",   "content", buildUserMessage(controllerJson))
                )
        ));

        onProgress.accept("[2/4] 正在连接 AI 服务 → " + url + "…");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                .header("Content-Type", "application/json");

        // Only add Authorization header when a key is provided (Ollama/local models skip it)
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        onProgress.accept("[3/4] 请求已发出，等待 AI 响应（最长 " + REQUEST_TIMEOUT_SEC + "s）…");
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "AI API returned HTTP " + response.statusCode() + ":\n" + response.body());
        }

        onProgress.accept("[4/4] 收到响应 (HTTP " + response.statusCode() + ")，正在解析内容…");
        return parseContent(response.body());
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private static @NotNull String buildSystemPrompt() {
        return """
                你是一位专业的 API 文档工程师，擅长根据 Spring Boot 控制器的元数据和实现代码生成结构清晰的接口文档。
                
                请使用 Markdown 格式输出文档，每个接口包含以下内容：
                1. **接口概述** — 一句话描述接口用途
                2. **请求参数** — 表格（字段名 | 类型 | 必填 | 说明）
                3. **响应结构** — 表格（字段名 | 类型 | 说明）
                4. **请求示例** — JSON 代码块
                5. **响应示例** — JSON 代码块
                6. **业务逻辑说明** — 根据 methodBody 推断的业务规则、异常处理和副作用
                
                如果 methodBody 为空，则跳过第 6 点。
                输出语言：中文。
                """;
    }

    private static @NotNull String buildUserMessage(@NotNull String controllerJson) {
        return "以下是扫描到的 Spring Controller 接口元数据（含方法体代码），请为每个 endpoint 生成完整的接口文档：\n\n"
                + "```json\n" + controllerJson + "\n```";
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Parses {@code choices[0].message.content} from the standard
     * OpenAI chat-completions JSON response.
     */
    @SuppressWarnings("unchecked")
    private static @NotNull String parseContent(@NotNull String responseBody) {
        Map<String, Object> resp = GSON.fromJson(responseBody, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI response contained no choices:\n" + responseBody);
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("AI response choice had no message:\n" + responseBody);
        }
        Object content = message.get("content");
        if (content == null) {
            throw new RuntimeException("AI response message had no content:\n" + responseBody);
        }
        return content.toString();
    }
}
