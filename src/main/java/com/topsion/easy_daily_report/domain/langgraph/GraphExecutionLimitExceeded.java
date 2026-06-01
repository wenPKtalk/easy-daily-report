package com.topsion.easy_daily_report.domain.langgraph;

public class GraphExecutionLimitExceeded extends RuntimeException {

    public GraphExecutionLimitExceeded(int limit) {
        super("Graph execution exceeded maxTotalNodeExecutions=" + limit);
    }
}
