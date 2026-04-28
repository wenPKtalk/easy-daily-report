package com.topsion.easy_daily_report.infrastructure.jira;

import com.topsion.easy_daily_report.domain.model.JiraIssueInfo;
import com.topsion.easy_daily_report.domain.port.JiraPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Jira REST API 适配器（Adapter）
 * 实现 JiraPort 端口，使用 Java HttpClient 访问 Jira REST API
 *
 * 设计模式：Adapter Pattern — 将 Jira REST API 适配为 Domain 端口接口
 */
@Component
@Slf4j
public class JiraRestAdapter implements JiraPort {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String authHeader;

    public JiraRestAdapter(
            @Value("${jira.base-url}") String baseUrl,
            @Value("${jira.username}") String username,
            @Value("${jira.api-token}") String apiToken
    ) {
        this.baseUrl = baseUrl;
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + apiToken).getBytes());
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public JiraIssueInfo getIssue(String issueKey) {
        log.info("获取 Jira Issue: {}", issueKey);
        try {
            String url = baseUrl + "/rest/api/2/issue/" + issueKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Jira API 返回错误状态码: {}", response.statusCode());
                throw new RuntimeException("Jira API 错误: " + response.statusCode());
            }

            return parseIssueResponse(issueKey, response.body());
        } catch (IOException | InterruptedException e) {
            log.error("获取 Jira Issue 失败: {}", issueKey, e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取 Jira Issue 失败: " + issueKey, e);
        }
    }

    /**
     * 简易 JSON 解析（MVP 阶段）
     * TODO: 后续可引入 Jackson ObjectMapper 做完整解析
     */
    private JiraIssueInfo parseIssueResponse(String issueKey, String responseBody) {
        return new JiraIssueInfo(
                issueKey,
                extractJsonField(responseBody, "summary"),
                extractJsonField(responseBody, "description"),
                extractJsonField(responseBody, "status"),
                extractJsonField(responseBody, "assignee"),
                extractJsonField(responseBody, "priority")
        );
    }

    private String extractJsonField(String json, String field) {
        // MVP: 简单的字段提取，生产环境应使用 Jackson
        int idx = json.indexOf("\"" + field + "\"");
        if (idx == -1) return "";
        int valueStart = json.indexOf(":", idx) + 1;
        int valueEnd = json.indexOf(",", valueStart);
        if (valueEnd == -1) valueEnd = json.indexOf("}", valueStart);
        String value = json.substring(valueStart, valueEnd).trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
