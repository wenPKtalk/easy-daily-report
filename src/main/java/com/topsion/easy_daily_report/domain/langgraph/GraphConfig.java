package com.topsion.easy_daily_report.domain.langgraph;

public record GraphConfig(int maxTotalNodeExecutions) {

    public static GraphConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int maxTotalNodeExecutions = 50;

        public Builder maxTotalNodeExecutions(int value) {
            this.maxTotalNodeExecutions = value;
            return this;
        }

        public GraphConfig build() {
            return new GraphConfig(maxTotalNodeExecutions);
        }
    }
}
