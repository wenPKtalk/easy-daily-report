package com.topsion.easy_daily_report.application.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class AgentRouterTest {

    @Mock
    private GenerateReportUseCase singleAgent;

    @Mock
    private MultiAgentOrchestrator multiAgent;

    private AgentRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentRouter(singleAgent, multiAgent);
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
    @DisplayName("route(COORDINATOR_AGENT) throws UnsupportedOperationException")
    void route_coordinatorAgent_throws() {
        assertThatThrownBy(() -> router.route(AgentLevel.COORDINATOR_AGENT))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
