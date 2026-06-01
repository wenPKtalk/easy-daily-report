package com.topsion.easy_daily_report.domain.langgraph;

public interface Node<S> {

    NodeId id();

    S execute(S state);
}
