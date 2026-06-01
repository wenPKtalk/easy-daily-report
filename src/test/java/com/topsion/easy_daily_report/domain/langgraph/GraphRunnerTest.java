package com.topsion.easy_daily_report.domain.langgraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRunnerTest {

    record TestState(String trace) {
        TestState append(String token) {
            return new TestState(trace == null ? token : trace + "," + token);
        }
    }

    private static Node<TestState> stub(NodeId id, String token) {
        return new Node<>() {
            @Override public NodeId id() { return id; }
            @Override public TestState execute(TestState state) { return state.append(token); }
        };
    }

    @Test
    @DisplayName("single node + Terminal: executes the node once and returns its output state")
    void singleNodeTerminal_executesOnce() {
        NodeId a = NodeId.of("A");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .edge(new Edge.Terminal<>(a))
                .build();

        GraphRunner<TestState> runner = new GraphRunner<>(GraphConfig.defaults());
        TestState result = runner.run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A");
    }

    @Test
    @DisplayName("Graph.build rejects Direct edge pointing to an unregistered node")
    void build_dangingDirectTarget_throws() {
        NodeId a = NodeId.of("A");
        NodeId ghost = NodeId.of("GHOST");

        Graph.Builder<TestState> builder = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .edge(new Edge.Direct<>(a, ghost))
                .edge(new Edge.Terminal<>(ghost));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalGraphException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    @DisplayName("Graph.build rejects Conditional target that isn't registered")
    void build_unregisteredConditionalTarget_throws() {
        NodeId a = NodeId.of("A");
        NodeId real = NodeId.of("REAL");
        NodeId ghost = NodeId.of("GHOST");

        Graph.Builder<TestState> builder = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(real, "R"))
                .edge(new Edge.Conditional<>(a, s -> real, java.util.Set.of(real, ghost)))
                .edge(new Edge.Terminal<>(real));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalGraphException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    @DisplayName("Graph.build rejects FanOut target that isn't registered")
    void build_unregisteredFanOutTarget_throws() {
        NodeId a = NodeId.of("A");
        NodeId b = NodeId.of("B");
        NodeId ghost = NodeId.of("GHOST");
        NodeId d = NodeId.of("D");

        Graph.Builder<TestState> builder = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(b, "B"))
                .node(stub(d, "D"))
                .edge(new Edge.FanOut<>(a, java.util.List.of(b, ghost)))
                .edge(new Edge.BarrierJoin<>(java.util.Set.of(b, ghost), d, (base, branches) -> base))
                .edge(new Edge.Terminal<>(d));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalGraphException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    @DisplayName("Graph.build rejects start id with no registered node")
    void build_unregisteredStart_throws() {
        NodeId a = NodeId.of("A");

        Graph.Builder<TestState> builder = Graph.<TestState>builder()
                .start(a)
                .edge(new Edge.Terminal<>(a));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalGraphException.class)
                .hasMessageContaining("A");
    }

    @Test
    @DisplayName("FanOut + BarrierJoin: two single-node branches merge then advance to join target")
    void fanOutBarrierJoin_mergesBranches() {
        NodeId a = NodeId.of("A");
        NodeId b = NodeId.of("B");
        NodeId c = NodeId.of("C");
        NodeId d = NodeId.of("D");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(b, "B"))
                .node(stub(c, "C"))
                .node(stub(d, "D"))
                .edge(new Edge.FanOut<>(a, java.util.List.of(b, c)))
                .edge(new Edge.BarrierJoin<>(
                        java.util.Set.of(b, c), d,
                        (base, branches) -> base.append(
                                branches.stream()
                                        .map(s -> s.trace().substring(s.trace().lastIndexOf(',') + 1))
                                        .sorted()
                                        .collect(java.util.stream.Collectors.joining("+")))))
                .edge(new Edge.Terminal<>(d))
                .build();

        TestState result = new GraphRunner<TestState>(GraphConfig.defaults())
                .run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A,B+C,D");
    }

    @Test
    @DisplayName("Runaway loop: GraphRunner bails out after maxTotalNodeExecutions with typed exception")
    void runawayLoop_tripsHardCap() {
        NodeId a = NodeId.of("A");
        NodeId decider = NodeId.of("D");
        NodeId exit = NodeId.of("X");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(decider, "D"))
                .node(stub(exit, "X"))
                .edge(new Edge.Direct<>(a, decider))
                .edge(new Edge.Conditional<>(decider, s -> a, java.util.Set.of(a, exit)))
                .edge(new Edge.Terminal<>(exit))
                .build();

        GraphConfig cfg = GraphConfig.builder().maxTotalNodeExecutions(10).build();

        assertThatThrownBy(() -> new GraphRunner<TestState>(cfg).run(graph, new TestState(null)))
                .isInstanceOf(GraphExecutionLimitExceeded.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("Conditional loop-back: A → decider → A repeats until counter exits to Terminal")
    void conditionalLoopBack_terminatesViaCounter() {
        NodeId a = NodeId.of("A");
        NodeId decider = NodeId.of("D");
        NodeId exit = NodeId.of("X");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(decider, "D"))
                .node(stub(exit, "X"))
                .edge(new Edge.Direct<>(a, decider))
                .edge(new Edge.Conditional<>(
                        decider,
                        s -> s.trace().split(",").length >= 6 ? exit : a,
                        java.util.Set.of(a, exit)))
                .edge(new Edge.Terminal<>(exit))
                .build();

        TestState result = new GraphRunner<TestState>(GraphConfig.defaults())
                .run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A,D,A,D,A,D,X");
    }

    @Test
    @DisplayName("Conditional edge routes to left branch when router selects it")
    void conditional_routesLeft() {
        NodeId a = NodeId.of("A");
        NodeId left = NodeId.of("L");
        NodeId right = NodeId.of("R");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(left, "L"))
                .node(stub(right, "R"))
                .edge(new Edge.Conditional<>(a, s -> left, java.util.Set.of(left, right)))
                .edge(new Edge.Terminal<>(left))
                .edge(new Edge.Terminal<>(right))
                .build();

        TestState result = new GraphRunner<TestState>(GraphConfig.defaults())
                .run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A,L");
    }

    @Test
    @DisplayName("Conditional edge routes to right branch when router selects it")
    void conditional_routesRight() {
        NodeId a = NodeId.of("A");
        NodeId left = NodeId.of("L");
        NodeId right = NodeId.of("R");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(left, "L"))
                .node(stub(right, "R"))
                .edge(new Edge.Conditional<>(a, s -> right, java.util.Set.of(left, right)))
                .edge(new Edge.Terminal<>(left))
                .edge(new Edge.Terminal<>(right))
                .build();

        TestState result = new GraphRunner<TestState>(GraphConfig.defaults())
                .run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A,R");
    }

    @Test
    @DisplayName("two nodes via Direct edge: executes A then B")
    void twoNodesDirect_executesInOrder() {
        NodeId a = NodeId.of("A");
        NodeId b = NodeId.of("B");

        Graph<TestState> graph = Graph.<TestState>builder()
                .start(a)
                .node(stub(a, "A"))
                .node(stub(b, "B"))
                .edge(new Edge.Direct<>(a, b))
                .edge(new Edge.Terminal<>(b))
                .build();

        GraphRunner<TestState> runner = new GraphRunner<>(GraphConfig.defaults());
        TestState result = runner.run(graph, new TestState(null));

        assertThat(result.trace()).isEqualTo("A,B");
    }
}
