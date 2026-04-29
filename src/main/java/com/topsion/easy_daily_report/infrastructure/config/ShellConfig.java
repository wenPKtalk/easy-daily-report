package com.topsion.easy_daily_report.infrastructure.config;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.shell.jline.PromptProvider;
import org.springframework.stereotype.Component;

@Component
public class ShellConfig {
    @Bean
    public PromptProvider promptProvider() {
        return () -> new AttributedString(
                "Topsion > ",
                AttributedStyle.BOLD.foreground(AttributedStyle.GREEN)
        );
    }
}
