package com.topsion.easy_daily_report;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")   // 排除交互式 SlashShell（@Profile("!test")），避免其 System.exit 杀掉测试 JVM
class EasyDailyReportApplicationTests {

	@MockitoBean
	private EmbeddingStore<TextSegment> embeddingStore;

	@MockitoBean
	private ChatModel chatModel;

	@Test
	void contextLoads() {
	}

}
