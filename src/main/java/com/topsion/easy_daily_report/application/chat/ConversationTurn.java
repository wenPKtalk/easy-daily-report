package com.topsion.easy_daily_report.application.chat;

import java.time.LocalDateTime;

public record ConversationTurn(String role, String content, LocalDateTime at) {}
