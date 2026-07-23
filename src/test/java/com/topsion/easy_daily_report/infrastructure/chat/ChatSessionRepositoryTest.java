package com.topsion.easy_daily_report.infrastructure.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.chat.ConversationTurn;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatSessionRepository 针对真实嵌入式 H2 的集成测试。
 * <p>
 * 目的：证明移除 Postgres 后，chat/session 的 SQL 迁移到 H2 后确实可用——
 * 跑真实 schema（{@code db/init-chat-tables.sql}）+ MERGE upsert + DATEADD 时间窗 + 往返读写，
 * 全程无需任何数据库服务器。
 */
class ChatSessionRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ChatSessionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:chat_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        ((DriverManagerDataSource) ds).setDriverClassName("org.h2.Driver");
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/init-chat-tables.sql"));
        }
        jdbcTemplate = new JdbcTemplate(ds);
        repository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
    }

    private ChatSession session(String id, String user, AgentLevel mode, boolean overridden,
                                Map<String, String> ctx, List<ConversationTurn> history,
                                LocalDateTime lastActive) {
        LocalDateTime now = LocalDateTime.now();
        return new ChatSession(id, user, mode, overridden, ctx, history, now, lastActive);
    }

    @Test
    @DisplayName("save then findActiveSession round-trips fields + context map (H2 MERGE upsert)")
    void saveAndFind_roundTrips() {
        String id = UUID.randomUUID().toString();
        repository.save(session(id, "alice@test.com", AgentLevel.COORDINATOR_AGENT, true,
                Map.of("commitHash", "abc123"), List.of(), LocalDateTime.now()));

        Optional<ChatSession> found = repository.findActiveSession("alice@test.com");

        assertThat(found).isPresent();
        assertThat(found.get().sessionId()).isEqualTo(id);
        assertThat(found.get().currentMode()).isEqualTo(AgentLevel.COORDINATOR_AGENT);
        assertThat(found.get().modeOverridden()).isTrue();
        assertThat(found.get().context()).containsEntry("commitHash", "abc123");
    }

    @Test
    @DisplayName("save twice with same id upserts (one row, mode updated)")
    void save_isUpsert() {
        String id = UUID.randomUUID().toString();
        repository.save(session(id, "bob@test.com", AgentLevel.SINGLE, false,
                Map.of(), List.of(), LocalDateTime.now()));
        repository.save(session(id, "bob@test.com", AgentLevel.SAMPLE_MULTIPLE, true,
                Map.of("k", "v"), List.of(), LocalDateTime.now()));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_sessions WHERE session_id = ?", Integer.class, id);
        assertThat(rows).isEqualTo(1);
        assertThat(repository.findActiveSession("bob@test.com").orElseThrow().currentMode())
                .isEqualTo(AgentLevel.SAMPLE_MULTIPLE);
    }

    @Test
    @DisplayName("findActiveSession excludes sessions older than the 24h window (DATEADD interval)")
    void findActiveSession_staleExcluded() {
        repository.save(session(UUID.randomUUID().toString(), "carol@test.com", AgentLevel.SINGLE, false,
                Map.of(), List.of(), LocalDateTime.now().minusHours(48)));

        assertThat(repository.findActiveSession("carol@test.com")).isEmpty();
    }

    @Test
    @DisplayName("saveMostRecentTurn persists the last turn; findRecentTurns reads it back")
    void turns_roundTrip() {
        String id = UUID.randomUUID().toString();
        ConversationTurn turn = new ConversationTurn("user", "生成今天的日报", LocalDateTime.now());
        repository.save(session(id, "dave@test.com", AgentLevel.SINGLE, false,
                Map.of(), List.of(turn), LocalDateTime.now()));

        List<ConversationTurn> turns = repository.findRecentTurns(id, 10);

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).role()).isEqualTo("user");
        assertThat(turns.get(0).content()).isEqualTo("生成今天的日报");
    }
}
