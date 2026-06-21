package com.example.testplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * YApi 同步客户端。根据扫描到的 controller 元数据新增或更新 YApi 接口。
 * 使用 OkHttp3 进行 HTTP 请求。
 */
public final class YapiSyncClient {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type MAP_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private YapiSyncClient() {
    }

    public static @NotNull String sync(
            @NotNull String scannedJson,
            @Nullable String aiDocMarkdown,
            @NotNull String yapiBaseUrl,
            @NotNull String authToken,
            int projectId,
            int catId,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String baseUrl = normalizeBaseUrl(yapiBaseUrl);

        onProgress.accept("准备 YApi 同步请求...");
        Map<String, Object> root = GSON.fromJson(scannedJson, MAP_TYPE);
        List<Map<String, Object>> endpoints = root == null
                ? List.of()
                : (List<Map<String, Object>>) root.get("endpoints");
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("扫描结果中未找到任何 endpoint 元数据。");
        }

        onProgress.accept("正在读取 YApi 接口列表以确定新增/更新操作...");
        List<Map<String, Object>> existing = listExistingInterfaces(baseUrl, authToken, projectId, catId, onProgress);

        StringBuilder summary = new StringBuilder();
        int created = 0;
        int updated = 0;

        for (Map<String, Object> endpoint : endpoints) {
            String path = String.valueOf(endpoint.getOrDefault("url", ""));
            String method = String.valueOf(endpoint.getOrDefault("httpMethod", "GET")).toUpperCase();
            String name = buildInterfaceName(endpoint, aiDocMarkdown);
            String markdown = aiDocMarkdown == null ? "" : aiDocMarkdown;
            String desc = buildShortDescription(aiDocMarkdown, endpoint);

            OptionalInt maybeId = findExistingInterfaceId(existing, path, method);
            if (maybeId.isPresent()) {
                int id = maybeId.getAsInt();
                onProgress.accept("更新 YApi 接口：" + method + " " + path + " (id=" + id + ")");
                updateInterface(baseUrl, authToken, buildUpdatePayload(id, projectId, catId, name, path, method, desc, markdown, endpoint), onProgress);
                updated++;
                summary.append("已更新: ").append(method).append(" ").append(path).append(" (id=").append(id).append(")\n");
            } else {
                onProgress.accept("新增 YApi 接口：" + method + " " + path);
                int id = createInterface(baseUrl, authToken, buildCreatePayload(projectId, catId, name, path, method, desc, markdown, endpoint), onProgress);
                created++;
                summary.append("已新增: ").append(method).append(" ").append(path).append(" (id=").append(id).append(")\n");
            }
        }

        summary.append('\n')
                .append("同步完成，新增 ").append(created).append(" 个接口，更新 ").append(updated).append(" 个接口。");
        return summary.toString();
    }

    private static @NotNull String normalizeBaseUrl(@NotNull String url) {
        String result = url.trim();
        if (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static @NotNull List<Map<String, Object>> listExistingInterfaces(
            @NotNull String baseUrl,
            @NotNull String authToken,
            int projectId,
            int catId,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String url = baseUrl + "/api/interface/list?project_id=" + projectId
                + "&catid=" + catId + "&page=1&limit=1000&token=" + authToken;

        onProgress.accept("GET " + url);
        String body = sendGetRequest(url);
        Map<String, Object> parsed = GSON.fromJson(body, MAP_TYPE);
        Object data = parsed.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object list = dataMap.get("list");
        if (!(list instanceof List<?> listObj)) {
            return List.of();
        }
        return (List<Map<String, Object>>) listObj;
    }

    public static @NotNull List<Map<String, Object>> listInterfaces(
            @NotNull String yapiBaseUrl,
            @NotNull String authToken,
            int projectId,
            int catId,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String baseUrl = normalizeBaseUrl(yapiBaseUrl);
        return listExistingInterfaces(baseUrl, authToken, projectId, catId, onProgress);
    }

    public static @NotNull Map<String, Object> getInterfaceDetail(
            @NotNull String yapiBaseUrl,
            @NotNull String authToken,
            int interfaceId,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String baseUrl = normalizeBaseUrl(yapiBaseUrl);
        String url = baseUrl + "/api/interface/get?id=" + interfaceId + "&token=" + authToken;
        onProgress.accept("GET " + url);
        String body = sendGetRequest(url);
        ensureSuccess(body, "获取接口详情失败");
        Map<String, Object> parsed = GSON.fromJson(body, MAP_TYPE);
        Object data = parsed.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            return (Map<String, Object>) dataMap;
        }
        return Map.of();
    }

    private static OptionalInt findExistingInterfaceId(
            @NotNull List<Map<String, Object>> existing,
            @NotNull String path,
            @NotNull String method
    ) {
        for (Map<String, Object> item : existing) {
            String existingPath = String.valueOf(item.getOrDefault("path", ""));
            String existingMethod = String.valueOf(item.getOrDefault("method", "")).toUpperCase();
            if (existingPath.equals(path) && existingMethod.equals(method)) {
                Object idValue = item.get("_id");
                if (idValue == null) {
                    idValue = item.get("id");
                }
                try {
                    return OptionalInt.of(Integer.parseInt(String.valueOf(idValue)));
                } catch (NumberFormatException ignored) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.empty();
    }

    private static @NotNull Map<String, Object> buildCreatePayload(
            int projectId,
            int catId,
            @NotNull String name,
            @NotNull String path,
            @NotNull String method,
            @NotNull String desc,
            @NotNull String markdown,
            @NotNull Map<String, Object> endpoint
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", "");  // Will be set by caller
        payload.put("project_id", projectId);
        payload.put("catid", catId);
        payload.put("name", name);
        payload.put("title", name);
        payload.put("path", path);
        payload.put("method", method);
        payload.put("status", "undone");
        payload.put("desc", desc);
        payload.put("description", desc);
        payload.put("markdown", markdown);
        payload.put("req_params", buildReqParams((List<Map<String, Object>>) endpoint.get("parameters")));
        payload.put("req_body_type", "json");
        payload.put("req_body_other", buildReqBody((List<Map<String, Object>>) endpoint.get("parameters")));
        payload.put("req_body_is_json_schema", false);
        payload.put("res_body", buildResBody((Map<String, Object>) endpoint.get("returnType")));
        payload.put("res_body_type", "json");
        payload.put("res_body_is_json_schema", false);
        return payload;
    }

    private static @NotNull Map<String, Object> buildUpdatePayload(
            int id,
            int projectId,
            int catId,
            @NotNull String name,
            @NotNull String path,
            @NotNull String method,
            @NotNull String desc,
            @NotNull String markdown,
            @NotNull Map<String, Object> endpoint
    ) {
        Map<String, Object> payload = buildCreatePayload(projectId, catId, name, path, method, desc, markdown, endpoint);
        payload.put("id", id);
        return payload;
    }

    private static @NotNull List<Map<String, Object>> buildReqParams(@Nullable List<Map<String, Object>> parameters) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (parameters == null) {
            return result;
        }
        for (Map<String, Object> param : parameters) {
            String binding = String.valueOf(param.getOrDefault("binding", "none"));
            String name = String.valueOf(param.getOrDefault("name", ""));
            String type = String.valueOf(param.getOrDefault("type", "string"));
            if (binding.equals("RequestBody")) {
                continue;
            }
            String paramType = switch (binding) {
                case "PathVariable" -> "path";
                case "RequestParam" -> "query";
                default -> "query";
            };
            Map<String, Object> item = new HashMap<>();
            item.put("name", name);
            item.put("value", "");
            item.put("desc", "");
            item.put("required", 1);
            item.put("type", type);
            item.put("paramType", paramType);
            result.add(item);
        }
        return result;
    }

    private static @NotNull String buildReqBody(@Nullable List<Map<String, Object>> parameters) {
        if (parameters == null) {
            return "";
        }
        Map<String, Object> body = new HashMap<>();
        for (Map<String, Object> param : parameters) {
            if ("RequestBody".equals(param.getOrDefault("binding", ""))) {
                String name = String.valueOf(param.getOrDefault("name", "body"));
                body.put(name, buildExampleValue(param.get("type")));
            }
        }
        return body.isEmpty() ? "{}" : GSON.toJson(body);
    }

    private static @NotNull String buildResBody(@Nullable Map<String, Object> returnType) {
        if (returnType == null) {
            return "{}";
        }
        Object fields = returnType.get("fields");
        if (!(fields instanceof List<?> fieldList)) {
            return "{}";
        }
        Map<String, Object> example = new HashMap<>();
        for (Object item : fieldList) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) item;
            String name = String.valueOf(field.getOrDefault("name", "field"));
            example.put(name, buildExampleValue(field.get("type")));
        }
        return example.isEmpty() ? "{}" : GSON.toJson(example);
    }

    private static @NotNull Object buildExampleValue(@Nullable Object typeValue) {
        String type = typeValue == null ? "string" : String.valueOf(typeValue).toLowerCase();
        return switch (type) {
            case "int", "integer", "long", "short", "byte" -> 0;
            case "float", "double", "bigdecimal" -> 0.0;
            case "boolean" -> false;
            case "list", "array" -> List.of();
            case "map", "object" -> Map.of();
            default -> "string";
        };
    }

    private static @NotNull String buildInterfaceName(
            @NotNull Map<String, Object> endpoint,
            @Nullable String aiDocMarkdown
    ) {
        if (aiDocMarkdown != null && aiDocMarkdown.contains("\n")) {
            String firstLine = aiDocMarkdown.split("\r?\n")[0].trim();
            if (!firstLine.isEmpty()) {
                return firstLine.length() > 50 ? firstLine.substring(0, 50) : firstLine;
            }
        }
        return String.valueOf(endpoint.getOrDefault("methodName", "接口"));
    }

    private static @NotNull String buildShortDescription(
            @Nullable String aiDocMarkdown,
            @NotNull Map<String, Object> endpoint
    ) {
        if (aiDocMarkdown != null && !aiDocMarkdown.isBlank()) {
            String[] lines = aiDocMarkdown.split("\r?\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    return line.length() > 120 ? line.substring(0, 120) : line;
                }
            }
        }
        return String.valueOf(endpoint.getOrDefault("javadoc", ""));
    }

    // ── HTTP Methods using OkHttp ────────────────────────────────────────

    private static @NotNull String sendGetRequest(@NotNull String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", "application/json")
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("YApi GET 请求失败 HTTP " + response.code() + ": " + body + "\n请求地址: " + url);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    private static @NotNull String sendPostRequest(@NotNull String url, @NotNull String jsonBody) throws Exception {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String respBody = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("YApi POST 请求失败 HTTP " + response.code() + ": " + respBody + "\n请求地址: " + url);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    private static void updateInterface(
            @NotNull String baseUrl,
            @NotNull String authToken,
            @NotNull Map<String, Object> payload,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String url = baseUrl + "/api/interface/change";
        payload.put("token", authToken);
        String jsonBody = GSON.toJson(payload);
        Path dump = null;
        try {
            dump = writeDebugFile("yapi-change-", jsonBody);
            onProgress.accept("POST " + url + "\npayload saved to: " + dump + "\n(Showing truncated preview)\n" + (jsonBody.length() > 2000 ? jsonBody.substring(0, 2000) + "...(truncated)" : jsonBody));
            onProgress.accept("可在终端复现： " + buildCurlCommand(url, dump));
        } catch (Exception ex) {
            onProgress.accept("无法写入调试文件： " + ex.getMessage());
            onProgress.accept("POST " + url + "\npayload preview: " + (jsonBody.length() > 2000 ? jsonBody.substring(0, 2000) + "...(truncated)" : jsonBody));
        }
        String response = sendPostRequest(url, jsonBody);
        onProgress.accept("响应: " + (response == null ? "" : (response.length() > 2000 ? response.substring(0, 2000) + "...(truncated)" : response)));
        ensureSuccess(response, "更新接口失败");
    }

    private static int createInterface(
            @NotNull String baseUrl,
            @NotNull String authToken,
            @NotNull Map<String, Object> payload,
            @NotNull Consumer<String> onProgress
    ) throws Exception {
        String url = baseUrl + "/api/interface/add";
        payload.put("token", authToken);
        String jsonBody = GSON.toJson(payload);
        Path dump = null;
        try {
            dump = writeDebugFile("yapi-add-", jsonBody);
            onProgress.accept("POST " + url + "\npayload saved to: " + dump + "\n(Showing truncated preview)\n" + (jsonBody.length() > 2000 ? jsonBody.substring(0, 2000) + "...(truncated)" : jsonBody));
            onProgress.accept("可在终端复现： " + buildCurlCommand(url, dump));
        } catch (Exception ex) {
            onProgress.accept("无法写入调试文件： " + ex.getMessage());
            onProgress.accept("POST " + url + "\npayload preview: " + (jsonBody.length() > 2000 ? jsonBody.substring(0, 2000) + "...(truncated)" : jsonBody));
        }
        String response = sendPostRequest(url, jsonBody);
        onProgress.accept("响应: " + (response == null ? "" : (response.length() > 2000 ? response.substring(0, 2000) + "...(truncated)" : response)));
        Map<String, Object> parsed = GSON.fromJson(response, MAP_TYPE);
        ensureSuccess(response, "新增接口失败");
        Object data = parsed.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object id = dataMap.get("_id");
            if (id == null) {
                id = dataMap.get("id");
            }
            if (id != null) {
                return Integer.parseInt(String.valueOf(id));
            }
        }
        return -1;
    }

    private static void ensureSuccess(@NotNull String response, @NotNull String message) {
        Map<String, Object> parsed = GSON.fromJson(response, MAP_TYPE);
        Object code = parsed.get("code");
        if (code != null && !String.valueOf(code).equals("0")) {
            throw new RuntimeException(message + ": " + response);
        }
    }

    // ── Debug helpers ─────────────────────────────────────────────────────

    private static @NotNull Path writeDebugFile(@NotNull String prefix, @NotNull String content) throws Exception {
        String name = prefix + Instant.now().toString().replace(':', '-') + ".json";
        Path dir = Path.of(System.getProperty("user.dir"), "build", "yapi-debug");
        if (!Files.exists(dir)) Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    private static @NotNull String buildCurlCommand(@NotNull String url, @NotNull Path payloadFile) {
        return "curl -v -X POST '" + url + "' -H 'Content-Type: application/json' --data-binary @" + payloadFile.toAbsolutePath();
    }
}
