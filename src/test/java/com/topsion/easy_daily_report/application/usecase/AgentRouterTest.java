package com.topsion.easy_daily_report.application.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgentRouterTest {

    @Mock
    private GenerateReportUseCase singleAgent;

    @Mock
    private MultiAgentOrchestrator multiAgent;

    @Mock
    private CoordinatorOrchestrator coordinatorAgent;

    private AgentRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentRouter(singleAgent, multiAgent, coordinatorAgent);
    }

    @Test
    @DisplayName("route(SINGLE) returns GenerateReportUseCase")
    void route_single_returnsSingleAgent() {
        GenerateAgent agent = router.route(AgentLevel.SINGLE);
        assertThat(agent).isSameAs(singleAgent);
    }

    @Test
    @DisplayName("route(SAMPLE_MULTIPLE) returns MultiAgentOrchestrator")
    void route_sampleMultiple_returnsMultiAgent() {
        GenerateAgent agent = router.route(AgentLevel.SAMPLE_MULTIPLE);
        assertThat(agent).isSameAs(multiAgent);
    }

    @Test
    @DisplayName("route(COORDINATOR_AGENT) returns CoordinatorOrchestrator")
    void route_coordinatorAgent_returnsCoordinator() {
        GenerateAgent agent = router.route(AgentLevel.COORDINATOR_AGENT);
        assertThat(agent).isSameAs(coordinatorAgent);
    }
}
