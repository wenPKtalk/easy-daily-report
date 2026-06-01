package com.topsion.easy_daily_report.domain.langgraph;

public record NodeId(String value) {

    /** Virtual node id used as {@code from()} for BarrierJoin edges. */
    public static final NodeId JOIN = new NodeId("__JOIN__");

    public static NodeId of(String value) {
        return new NodeId(value);
    }
}
