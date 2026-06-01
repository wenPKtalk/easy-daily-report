package com.topsion.easy_daily_report.domain.langgraph;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface Edge<S>
        permits Edge.Direct, Edge.Conditional, Edge.FanOut, Edge.BarrierJoin, Edge.Terminal {

    NodeId from();

    record Direct<S>(NodeId from, NodeId to) implements Edge<S> {}

    record Conditional<S>(NodeId from, Function<S, NodeId> router, Set<NodeId> targets)
            implements Edge<S> {}

    record FanOut<S>(NodeId from, List<NodeId> targets) implements Edge<S> {}

    record BarrierJoin<S>(Set<NodeId> sources, NodeId to, BiFunction<S, List<S>, S> merger)
            implements Edge<S> {

        @Override
        public NodeId from() {
            return NodeId.JOIN;
        }
    }

    record Terminal<S>(NodeId from) implements Edge<S> {}
}
