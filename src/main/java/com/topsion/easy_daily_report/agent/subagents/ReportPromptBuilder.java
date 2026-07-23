package com.topsion.easy_daily_report.agent.subagents;

/**
 * 组装「日报合成」用户消息的纯 Java 构造器。
 * <p>
 * 存在的理由：{@link ReportGeneratorAgent#generate(String)} 需要在同一条 user message 中
 * 拿到真实的 Git / Jira 分析 JSON 与日期。历史实现曾用 {@code @V} 参数传递这些数据，但模板里
 * 没有对应的 {@code {{占位符}}}，被 LangChain4j 静默丢弃，导致合成器收不到任何分析。
 * 这里把消息在 Java 侧显式拼好，可脱离 LLM 直接单元测试（断言产物包含分析 JSON）。
 */
public final class ReportPromptBuilder {

    private ReportPromptBuilder() {
    }

    /**
     * @param todayDate         今日日期（yyyy-MM-dd）
     * @param gitAnalysisJson   Git 代码变更分析 JSON
     * @param jiraAnalysisJson  Jira 业务需求分析 JSON
     * @param historicalContext 历史相似日报片段（可为 null / 空，为空则不追加该段）
     */
    public static String build(String todayDate,
                               String gitAnalysisJson,
                               String jiraAnalysisJson,
                               String historicalContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("请基于以下分析结果生成结构化工作日报。\n\n");
        sb.append("今日日期：").append(nullSafe(todayDate)).append("\n\n");
        sb.append("## Git 代码变更分析（JSON）\n").append(nullSafe(gitAnalysisJson)).append("\n\n");
        sb.append("## Jira 业务需求分析（JSON）\n").append(nullSafe(jiraAnalysisJson)).append("\n");

        if (historicalContext != null && !historicalContext.isBlank()) {
            sb.append("\n## 可参考的历史相似日报片段（仅供风格与模式参考，不要直接抄录）\n")
                    .append(historicalContext)
                    .append("\n");
        }
        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
