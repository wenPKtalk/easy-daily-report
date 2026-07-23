package com.topsion.easy_daily_report.agent.coordinator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * COORDINATOR_AGENT 模式的 Master Agent。
 * <p>
 * 通过工具调用（function-calling）循环动态调度 {@code SubAgentDelegationTool} 暴露的 4 个工具：
 * <ul>
 *   <li>{@code analyzeGitChanges} — 代码变更分析</li>
 *   <li>{@code analyzeJiraIssue} — 业务需求分析</li>
 *   <li>{@code retrieveSimilarReports} — 历史日报检索（RAG）</li>
 *   <li>{@code composeFinalReport} — 最终 Markdown 日报生成</li>
 * </ul>
 * 与 SAMPLE_MULTIPLE 的本质差异：调用哪些工具、是否使用 RAG、是否跳过某分析步骤由 LLM 依输入决定；
 * 调用顺序则受数据依赖约束（先分析后合成）。整体由 LLM 在运行时驱动，而非 Java 层固定编排。
 */
public interface CoordinatorAgent {

    @SystemMessage("""
            你是日报生成系统的协调者（Master Agent），负责在工具调用（function-calling）循环中调度专业工具完成工作日报生成。

            可用工具：
            1. analyzeGitChanges(commitHash) — 分析 Git 代码变更，返回 JSON 技术分析
            2. analyzeJiraIssue(jiraKey) — 分析 Jira 业务需求，返回 JSON 业务分析
            3. retrieveSimilarReports(query) — 检索历史相似日报，返回参考文本（可选步骤，用于借鉴风格与模式）
            4. composeFinalReport(gitAnalysisJson, jiraAnalysisJson, historicalContext, todayDate) — 整合所有输入并生成最终 Markdown 日报（必须最后调用）

            执行约束：
            - 必须先调用分析类工具（1、2），再调用 composeFinalReport（4），不要颠倒顺序。
            - 当输入的 commitHash 或 jiraKey 为空字符串时，**跳过**对应分析步骤，并在调用 composeFinalReport 时传入合理的默认 JSON 或空字符串。
            - retrieveSimilarReports 是可选的：当 Jira 业务背景明确、值得参考过去类似工作时再调用；查询关键词建议用业务背景或技术要点的短句。
            - 任意分析工具返回 "error" 字段时，不要重试同一工具，继续后续步骤，让最终报告自动降级。

            输出要求：
            - 最终返回 composeFinalReport 的输出原文（Markdown 字符串），不要添加额外的元信息、对话语或解释。
            """)
    @UserMessage("""
            请协调生成工作日报。任务上下文：
            Commit Hash: {{commitHash}}
            Jira Key:    {{jiraKey}}
            Today Date:  {{todayDate}}
            """)
    String coordinate(
            @V("commitHash") String commitHash,
            @V("jiraKey") String jiraKey,
            @V("todayDate") String todayDate
    );
}
