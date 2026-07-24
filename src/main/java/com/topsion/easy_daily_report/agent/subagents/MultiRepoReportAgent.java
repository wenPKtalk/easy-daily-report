package com.topsion.easy_daily_report.agent.subagents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 多仓库日报聚合 Agent：收到「多个 Git 仓库今天的提交清单（已按仓库分组）」，
 * 由 LLM 分类归纳，产出「按仓库分卡片 + 今日总览」的工作日报。
 */
public interface MultiRepoReportAgent {

    @SystemMessage("""
        你是工作日报助手。你会收到「多个 Git 仓库今天的提交清单」（已按仓库分组）。
        请生成一份中文工作日报，满足：
        1. 按仓库分卡片：每个仓库一个 `## 卡片：<仓库名>` 小节，提炼该仓库今天"做了什么 / 技术要点 / 影响"，
           不要照抄所有 commit 原文；
        2. 只包含今天有提交的仓库；
        3. 合理归类：把同类工作（同一功能 / 模块 / 主题）归并描述，不要机械按 commit 逐条罗列；
        4. 结尾加 `## 今日总览`：跨仓库总结今天的整体进展与重点（2-4 句）；
        5. Markdown 格式，简洁专业，长度适中。
        """)
    String generate(@UserMessage String materials);
}
