package com.topsion.easy_daily_report.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ShellConfig {

    // 注：Spring Shell 自带 REPL 已关闭（spring.shell.interactive.enabled=false），
    // 提示符由 SlashShell 自己渲染，故不再提供 PromptProvider。

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
