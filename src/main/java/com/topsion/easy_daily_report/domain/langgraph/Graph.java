package com.topsion.easy_daily_report.domain.langgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Graph<S> {

    private final NodeId start;
    private final Map<NodeId, Node<S>> nodes;
    private final List<Edge<S>> edges;

    private Graph(NodeId start, Map<NodeId, Node<S>> nodes, List<Edge<S>> edges) {
        this.start = start;
        this.nodes = Map.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    public NodeId start() {
        return start;
    }

    public Map<NodeId, Node<S>> nodes() {
        return nodes;
    }

    public List<Edge<S>> edges() {
        return edges;
    }

    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    public static final class Builder<S> {

        private NodeId start;
        private final Map<NodeId, Node<S>> nodes = new HashMap<>();
        private final List<Edge<S>> edges = new ArrayList<>();

        public Builder<S> start(NodeId id) {
            this.start = id;
            return this;
        }

        public Builder<S> node(Node<S> node) {
            nodes.put(node.id(), node);
            return this;
        }

        public Builder<S> edge(Edge<S> edge) {
            edges.add(edge);
            return this;
        }

        public Graph<S> build() {
            validate();
            return new Graph<>(start, nodes, edges);
        }

        private void validate() {
            if (start == null || !nodes.containsKey(start)) {
                throw new IllegalGraphException("start node not registered: " + start);
            }
            for (Edge<S> edge : edges) {
                switch (edge) {
                    case Edge.Direct<S> d -> requireRegistered(d.to());
                    case Edge.Conditional<S> c -> c.targets().forEach(this::requireRegistered);
                    case Edge.FanOut<S> f -> f.targets().forEach(this::requireRegistered);
                    case Edge.BarrierJoin<S> b -> requireRegistered(b.to());
                    case Edge.Terminal<S> t -> { }
                }
            }
        }

        private void requireRegistered(NodeId id) {
            if (!nodes.containsKey(id)) {
                throw new IllegalGraphException("edge references unregistered node: " + id);
            }
        }
    }
}
