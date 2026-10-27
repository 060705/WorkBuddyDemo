package com.example.demo.mcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通过 MCP 暴露的「外部系统」工具：查询城市天气。
 *
 * <p>这是演示 MCP 的关键 —— 它不像 TimeTool / KnowledgeBaseTool 那样是进程内的纯 Java 逻辑，
 * 而是真正去调用一个<b>外部 HTTP 服务</b>（wttr.in 免费天气 API，无需 Key）。
 *
 * <p>它会被 MCP Server 自动扫描并暴露成 MCP 工具（tools/list），任何 MCP Client
 * （包括本应用自己、Claude Desktop、其他 Agent 框架）都能通过标准协议调用它。
 */
@Component
public class WeatherMcpTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherMcpTool.class);

    /** 免费天气服务端点：返回人类可读的天气文本（无需 API Key） */
    private static final String WEATHER_ENDPOINT = "https://wttr.in/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 查询指定城市的当前天气。
     *
     * @param city 城市名（中文或英文，如「合肥」「北京」「Shanghai」）
     * @return 该城市的天气概况（温度、天气现象、湿度等）
     */
    @McpTool(name = "get_weather", description = "查询指定城市的实时天气。当用户询问某个城市今天天气怎么样、温度多少、下不下雨时使用。返回温度、天气现象、湿度、风向等。")
    public String getWeather(
            @McpToolParam(description = "要查询的城市名，中文或英文，例如：合肥、北京、上海、Shanghai") String city) {

        if (city == null || city.isBlank()) {
            return "（未提供城市名，请告诉我你想查哪个城市）";
        }

        try {
            // 请求 wttr.in，格式参数让返回更精简、适合 LLM 阅读
            String url = WEATHER_ENDPOINT + URLEncoder.encode(city.trim(), StandardCharsets.UTF_8)
                    + "?format=j1"; // j1 = JSON 格式，方便解析关键字段
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            if (resp.statusCode() != 200 || body == null || body.isBlank()) {
                log.warn("[MCP] 天气服务返回异常: city={}, httpStatus={}, bodyLen={}",
                        city, resp.statusCode(), body == null ? -1 : body.length());
                return "（天气服务暂不可用，HTTP " + resp.statusCode() + "）";
            }

            log.info("[MCP] 天气查询成功: city={}, httpStatus=200, bodyLen={}", city, body.length());
            // 直接返回摘要，交给模型自行归纳（这里保留原始 JSON 的关键片段，避免过度加工）
            return "城市：" + city.trim() + "\n天气服务返回（HTTP 200）：\n" + summarize(body);
        } catch (IOException | InterruptedException e) {
            log.warn("[MCP] 天气查询失败, city={}", city, e);
            return "（查询天气时网络异常：" + e.getMessage() + "）";
        }
    }

    /**
     * 从 wttr.in 的 j1 JSON 里抽几个关键字段，拼成精简摘要。
     * 不引 JSON 库，用轻量字符串提取（教学演示足够）。
     */
    private String summarize(String json) {
        StringBuilder sb = new StringBuilder();
        // current_condition 里通常有 temp_C、weatherDesc、humidity、windspeedKmph
        sb.append(extractField(json, "temp_C")).append("℃  ");
        sb.append(extractField(json, "FeelsLikeC")).append("℃（体感）  ");
        sb.append(extractWeatherDesc(json));
        sb.append("湿度 ").append(extractField(json, "humidity")).append("%  ");
        sb.append("风速 ").append(extractField(json, "windspeedKmph")).append("km/h");
        return sb.toString();
    }

    /** 提取形如 "temp_C":"18" 的字段值 */
    private String extractField(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) return "?";
        int start = idx + marker.length();
        // 跳过可能的空格或引号
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    /** 提取天气描述（weatherDesc 数组里的 value） */
    private String extractWeatherDesc(String json) {
        String marker = "\"weatherDesc\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return "";
        int valueIdx = json.indexOf("\"value\"", idx);
        if (valueIdx < 0) return "";
        int start = valueIdx + "\"value\"".length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"') end++;
        return "天气：" + json.substring(start, end).trim() + "  ";
    }
}
