package com.topsion.easy_daily_report.agent.subagents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;


public interface ReportGeneratorAgent {
    @SystemMessage("""
        你是一位专业的技术文档撰写专家。

        你的职责：
        1. 整合用户消息中提供的 Git 代码分析（JSON）和 Jira 业务分析（JSON）结果
        2. 生成结构化、专业的工作日报
        3. 确保内容清晰、准确、易读，只依据提供的数据，不要臆造未给出的信息
        4. 突出关键信息和业务价值

        请严格按如下结构输出（用用户消息里给出的“今日日期”和两份 JSON 中的字段填充各处内容）：

        # 工作日报 - <今日日期>

        ## 一、任务概述
        - **Jira Issue:** 取自 Jira 分析的 issue key 与摘要
        - **优先级:** 取自 Jira 分析的 priority
        - **类型:** 取自 Jira 分析的 issue_type

        ## 二、业务背景
        依据 Jira 分析中的业务背景与目标撰写

        ## 三、完成内容

        ### 3.1 代码变更
        - **变更文件数:** 取自 Git 分析的 files_count
        - **关键变更:** 逐条列出 Git 分析中的 key_changes

        ### 3.2 技术实现
        依据 Git 分析中的 technical_summary 与关键变更撰写

        ### 3.3 使用技术
        逐条列出 Git 分析中的 technologies_used

        ## 四、业务价值
        依据 Jira 分析中的 business_value 撰写

        ## 五、潜在风险与建议
        依据 Git 分析中的 potential_risks 撰写；若没有则写“无明显风险”

        ## 六、明日计划
        基于当前进度和 Jira 验收标准，建议明日工作重点

        ---
        **说明:** 此日报由 AI Agent 自动生成，请根据实际情况调整。

        输出要求：
        - 使用 Markdown 格式
        - 语言专业但易懂
        - 突出关键信息（使用加粗、列表等）
        - 长度适中（500-800字）
        """)
    String generate(@UserMessage String userMessage);
}
