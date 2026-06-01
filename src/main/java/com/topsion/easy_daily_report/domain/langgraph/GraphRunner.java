package com.topsion.easy_daily_report.domain.langgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GraphRunner<S> {

    private final GraphConfig config;

    public GraphRunner(GraphConfig config) {
        this.config = config;
    }

    public S run(Graph<S> graph, S initial) {
        int[] counter = {0};
        return runFrom(graph, graph.start(), initial, counter);
    }

    private S runFrom(Graph<S> graph, NodeId start, S initial, int[] counter) {
        S state = initial;
        NodeId current = start;
        while (current != null) {
            if (counter[0] >= config.maxTotalNodeExecutions()) {
                throw new GraphExecutionLimitExceeded(config.maxTotalNodeExecutions());
            }
            state = graph.nodes().get(current).execute(state);
            counter[0]++;

            Edge<S> out = findOutEdge(graph, current);
            if (out == null) {
                return state;
            }
            switch (out) {
                case Edge.Direct<S> d -> current = d.to();
                case Edge.Conditional<S> c -> current = c.router().apply(state);
                case Edge.Terminal<S> t -> { return state; }
                case Edge.FanOut<S> f -> {
                    S base = state;
                    List<S> branchOutputs = new ArrayList<>();
                    for (NodeId target : f.targets()) {
                        branchOutputs.add(runFrom(graph, target, base, counter));
                    }
                    Edge.BarrierJoin<S> barrier = findBarrier(graph, Set.copyOf(f.targets()));
                    state = barrier.merger().apply(base, branchOutputs);
                    current = barrier.to();
                }
                case Edge.BarrierJoin<S> b -> throw new IllegalStateException(
                        "BarrierJoin is not a valid out-edge for node " + current);
            }
        }
        return state;
    }

    private Edge<S> findOutEdge(Graph<S> graph, NodeId from) {
        for (Edge<S> edge : graph.edges()) {
            if (edge.from().equals(from)) {
                return edge;
            }
        }
        return null;
    }

    private Edge.BarrierJoin<S> findBarrier(Graph<S> graph, Set<NodeId> sources) {
        for (Edge<S> edge : graph.edges()) {
            if (edge instanceof Edge.BarrierJoin<S> b && b.sources().equals(sources)) {
                return b;
            }
        }
        throw new IllegalStateException("No BarrierJoin matches sources " + sources);
    }
}
