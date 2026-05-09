package com.topsion.easy_daily_report.agent.supervisor;

import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorAgentContractTest {

    @Test
    @DisplayName("SupervisorDecision has all required fields for routing")
    void supervisorDecision_hasAllRequiredFields() {
        SupervisorDecision decision = new SupervisorDecision(
            Intent.GENERATE_REPORT,
            AgentLevel.SAMPLE_MULTIPLE,
            "abc123",
            "PROJ-456",
            null,
            null
        );

        assertThat(decision.intent()).isEqualTo(Intent.GENERATE_REPORT);
        assertThat(decision.agentLevel()).isEqualTo(AgentLevel.SAMPLE_MULTIPLE);
        assertThat(decision.commitHash()).isEqualTo("abc123");
        assertThat(decision.jiraKey()).isEqualTo("PROJ-456");
    }

    @Test
    @DisplayName("Intent enum contains all expected values")
    void intent_enumHasAllValues() {
        assertThat(Intent.values()).contains(
            Intent.GENERATE_REPORT,
            Intent.FOLLOW_UP,
            Intent.CLARIFY,
            Intent.MODE_SWITCH
        );
    }
}
