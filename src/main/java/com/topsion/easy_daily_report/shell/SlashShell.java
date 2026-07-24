package com.topsion.easy_daily_report.shell;

import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 自研顶层「斜杠命令」REPL（仿 Claude Code）。
 * <p>
 * Spring Shell 4.0.1 会吃掉 {@code @Command} 里的 {@code /} 前缀、无法解析 {@code /generate} 这类命令，
 * 因此这里接管顶层交互：自建 jline LineReader（带 {@code /命令} 补全 + TAB 菜单），自己解析并分发到既有用例。
 * 配合 {@code spring.shell.interactive.enabled=false} 关闭 Spring Shell 自带 REPL；本 Runner 以最高优先级阻塞运行。
 */
@Slf4j
@Component
@Profile("!test")   // 测试上下文（@ActiveProfiles("test")）不启动交互 REPL，避免 System.exit 杀掉测试 JVM
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlashShell implements ApplicationRunner {

    private static final List<String> COMMANDS =
            List.of("/generate", "/generate-today", "/chat", "/help", "/exit");

    private final DailyReportCommands reportCommands;
    private final ChatCommands chatCommands;

    public SlashShell(DailyReportCommands reportCommands, ChatCommands chatCommands) {
        this.reportCommands = reportCommands;
        this.chatCommands = chatCommands;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Terminal terminal = buildSystemTerminal();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(COMMANDS))
                .option(LineReader.Option.INSERT_TAB, false)  // 关键：空行/行首按 TAB 也触发补全，而非插入制表符
                .option(LineReader.Option.AUTO_LIST, true)    // 有多个候选时自动列出
                .option(LineReader.Option.AUTO_MENU, true)    // 进入可反复 TAB 切换的候选菜单
                .build();

        printBanner();

        while (true) {
            String line;
            try {
                line = reader.readLine("Topsion > ");
            } catch (UserInterruptException e) {   // Ctrl+C：忽略当前行，继续
                continue;
            } catch (EndOfFileException e) {        // Ctrl+D / EOF：退出
                break;
            }
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!dispatch(line, reader)) break;
        }

        terminal.writer().println("再见 👋");
        terminal.flush();
        System.exit(0);
    }

    /** @return false 表示退出 REPL */
    private boolean dispatch(String line, LineReader reader) {
        List<String> tokens = List.of(line.split("\\s+"));
        String cmd = tokens.get(0);
        try {
            switch (cmd) {
                case "/generate" -> {
                    Args a = parseArgs(tokens);
                    print(reportCommands.generateReport(a.commit(), a.range(), a.jira(), a.repo(), a.level()));
                }
                case "/generate-today" -> {
                    Args a = parseArgs(tokens);
                    print(reportCommands.generateToday(a.jira(), a.repo(), a.level()));
                }
                case "/chat" -> chatCommands.startChatLoop(null, reader);
                case "/help" -> print(reportCommands.help());
                case "/exit", "exit", "quit" -> {
                    return false;
                }
                default -> System.out.println("未知命令：" + cmd + "，输入 /help 查看可用命令。");
            }
        } catch (Exception e) {
            log.error("命令执行失败: {}", line, e);
            System.out.println("❌ 执行失败：" + e.getMessage());
        }
        return true;
    }

    private void print(String s) {
        System.out.println(s == null ? "" : s);
    }

    /**
     * 依次尝试多个 jline provider，取第一个能建出「非 dumb」系统终端的。
     * fat-jar 里 jansi 原生库常加载失败导致退化 dumb（无 TAB 补全）；exec(用 stty) / jni 往往更稳。
     */
    private Terminal buildSystemTerminal() {
        StringBuilder diag = new StringBuilder("\n════════ 终端诊断 ════════\n")
                .append("TERM=").append(System.getenv("TERM"))
                .append("  System.console()=").append(System.console() != null ? "有" : "null")
                .append("  os=").append(System.getProperty("os.name"))
                .append("  java=").append(System.getProperty("java.version")).append("\n");
        Terminal fallback = null;
        for (String p : new String[]{null, "exec", "jni", "jansi", "ffm"}) {
            String name = p == null ? "auto" : p;
            try {
                TerminalBuilder b = TerminalBuilder.builder().system(true).dumb(false);
                if (p != null) {
                    b.provider(p);
                }
                Terminal t = b.build();
                diag.append("  provider=").append(name).append(" → type=").append(t.getType()).append("\n");
                if (!t.getType().startsWith("dumb")) {
                    System.out.print(diag.append("═══ 使用 provider=").append(name).append(" ═══\n"));
                    return t;
                }
                if (fallback == null) {
                    fallback = t;
                } else {
                    t.close();
                }
            } catch (Throwable e) {
                diag.append("  provider=").append(name).append(" → 失败: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        diag.append("═══ 全部 provider 未能建出真终端 → dumb（无 TAB 补全），把以上诊断贴给我 ═══\n");
        System.out.print(diag);
        if (fallback != null) {
            return fallback;
        }
        try {
            return TerminalBuilder.builder().system(true).dumb(true).build();
        } catch (IOException e) {
            throw new RuntimeException("无法创建终端", e);
        }
    }

    private void printBanner() {
        System.out.println("""
                ╔══════════════════════════════════════════════╗
                ║   Easy Daily Report — 输入 / 后按 TAB 选命令  ║
                ║   /generate  /generate-today  /chat  /help    ║
                ║   /exit 退出                                   ║
                ╚══════════════════════════════════════════════╝""");
    }

    /** 极简 flag 解析：-c/--commit  -r/--range  -j/--jira  -p/--repo  -l/--level */
    private record Args(String commit, String range, String jira, String repo, String level) {
    }

    private Args parseArgs(List<String> tokens) {
        String commit = null, range = null, jira = null, repo = null, level = null;
        for (int i = 1; i < tokens.size() - 1; i++) {
            String v = tokens.get(i + 1);
            switch (tokens.get(i)) {
                case "-c", "--commit" -> commit = v;
                case "-r", "--range" -> range = v;
                case "-j", "--jira" -> jira = v;
                case "-p", "--repo" -> repo = v;
                case "-l", "--level" -> level = v;
                default -> { }
            }
        }
        return new Args(commit, range, jira, repo, level);
    }
}
