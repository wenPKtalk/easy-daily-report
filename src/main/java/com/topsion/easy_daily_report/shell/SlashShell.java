package com.topsion.easy_daily_report.shell;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自研顶层「斜杠命令」REPL（仿 Claude Code），基于 Lanterna 纯 Java 真终端。
 * <p>
 * 背景：jline 在本项目运行环境（Spring Boot / Java 21）下所有 provider 都建不出真终端 → 退化 dumb → 无 TAB 补全；
 * Lanterna 走自己的 stty/ANSI（不依赖 jline 原生库），实测能拿到真终端（UnixTerminal），故整体切到 Lanterna。
 * <p>
 * 交互：直接读按键（方向键 / Tab / 回车 / 退格由 Lanterna 解码），输入 {@code /} 时在提示符下方渲染候选命令下拉菜单
 * （↑↓ 选择、Tab 填入、回车运行）。不进 private mode（保留滚动式输出，报告长文本可正常回滚）。
 * 配合 {@code spring.shell.interactive.enabled=false} 关闭 Spring Shell 自带 REPL；本 Runner 以最高优先级阻塞运行。
 */
@Slf4j
@Component
@Profile("!test")   // 测试上下文（@ActiveProfiles("test")）不启动交互 REPL，避免 System.exit 杀掉测试 JVM
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlashShell implements ApplicationRunner {

    /** 一条候选命令：名称 + 一句话说明（说明只用于菜单展示）。 */
    private record Cmd(String name, String desc) {
    }

    private static final List<Cmd> COMMANDS = List.of(
            new Cmd("/generate", "按 commit / 范围生成日报"),
            new Cmd("/generate-today", "汇总今天所有仓库的提交"),
            new Cmd("/chat", "进入多轮对话模式"),
            new Cmd("/help", "查看命令与参数说明"),
            new Cmd("/exit", "退出"));
    /** 菜单里命令名列宽（命令名均为 ASCII，按字符数对齐即可）。 */
    private static final int NAME_WIDTH = COMMANDS.stream().mapToInt(c -> c.name().length()).max().orElse(16) + 2;
    private static final String PROMPT = "❯ ";
    private static final String ESC = "\u001b[";   // CSI: ESC + [

    private static final String PLACEHOLDER = "输入 / 唤起命令";
    private static final String FOOTER = "↑↓ 选择   ⇥ 补全   ⏎ 运行   esc 清空";
    // SGR 颜色（TERM=xterm-256color）；注意：这些不可见字符不能计入光标列数
    private static final String RESET = ESC + "0m";
    private static final String C_PROMPT = ESC + "1;38;5;42m";   // 提示符 ❯：亮绿
    private static final String C_CMD = ESC + "38;5;44m";        // 命令名：青
    private static final String C_CMD_DIM = ESC + "38;5;30m";    // 未选中命令名：暗青
    private static final String C_MATCH = ESC + "1;38;5;214m";   // 已输入的匹配前缀：琥珀加粗
    private static final String C_DESC = ESC + "38;5;244m";      // 选中行说明：中灰
    private static final String C_DESC_DIM = ESC + "38;5;238m";  // 未选中行说明：暗灰
    private static final String C_HINT = ESC + "38;5;240m";      // 占位提示 / 底部提示：暗灰
    private static final String C_GHOST = ESC + "38;5;239m";     // 行内幽灵补全：极暗灰
    private static final String C_BAR = ESC + "38;5;213m";       // 选中行左侧色条：品红
    private static final String C_ARG = ESC + "38;5;180m";       // 参数：暖棕

    private final DailyReportCommands reportCommands;
    private final ChatCommands chatCommands;
    private Terminal terminal;

    public SlashShell(DailyReportCommands reportCommands, ChatCommands chatCommands) {
        this.reportCommands = reportCommands;
        this.chatCommands = chatCommands;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        terminal = new DefaultTerminalFactory().setForceTextTerminal(true).createTerminal();
        PrintStream original = System.out;
        System.setOut(crlfOut(original));   // raw 模式下 \n 可能不带 CR → 输出会阶梯错位，统一补成 \r\n
        try {
            printBanner();
            while (true) {
                String line = readLine(PROMPT, true);
                if (line == null) break;            // Ctrl+D / EOF
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!dispatch(line)) break;
            }
            System.out.println("再见 👋");
        } finally {
            System.setOut(original);
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 关闭失败无所谓，JVM 即将退出
            }
        }
        System.exit(0);
    }

    /** @return false 表示退出 REPL */
    private boolean dispatch(String line) {
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
                case "/chat" -> chatCommands.startChatLoop(null, p -> readLine(p, false));
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

    // ────────────────────────── 行编辑 + 下拉菜单 ──────────────────────────

    /**
     * 读一行输入。withMenu=true 时，输入以 {@code /} 开头且命令 token 未结束时，在下方渲染候选命令菜单。
     *
     * @return 输入的一行；Ctrl+D / EOF 返回 null；Ctrl+C 取消当前行返回空串
     */
    String readLine(String prompt, boolean withMenu) {
        StringBuilder buf = new StringBuilder();
        List<Cmd> menu = List.of();
        int sel = 0;
        try {
            render(prompt, buf, menu, sel, withMenu);
            while (true) {
                KeyStroke k = terminal.readInput();
                KeyType type = k.getKeyType();

                if (type == KeyType.EOF) {
                    return null;
                }
                if (k.isCtrlDown() && k.getCharacter() != null) {
                    char c = Character.toLowerCase(k.getCharacter());
                    if (c == 'd') {
                        return null;                 // Ctrl+D：退出
                    }
                    if (c == 'c') {
                        System.out.print("\r\n");
                        System.out.flush();
                        return "";                   // Ctrl+C：取消当前行
                    }
                }

                switch (type) {
                    case Enter -> {
                        render(prompt, buf, List.of(), sel, false);   // 空菜单+无提示重画 → 清掉菜单区（menu 可能不可变，不能 clear）
                        System.out.print("\r\n");
                        System.out.flush();
                        return buf.toString();
                    }
                    case Backspace -> {
                        if (buf.length() > 0) {
                            buf.deleteCharAt(buf.length() - 1);
                        }
                    }
                    case Tab -> {
                        if (withMenu && !menu.isEmpty()) {
                            buf.setLength(0);
                            buf.append(menu.get(sel).name()).append(' ');   // 填入所选命令，加空格准备接参数
                        }
                    }
                    case Escape -> {
                        buf.setLength(0);
                        sel = 0;
                    }
                    case ArrowDown -> {
                        if (!menu.isEmpty()) sel = (sel + 1) % menu.size();
                    }
                    case ArrowUp -> {
                        if (!menu.isEmpty()) sel = (sel - 1 + menu.size()) % menu.size();
                    }
                    case Character -> {
                        Character ch = k.getCharacter();
                        if (ch != null && !k.isCtrlDown()) buf.append(ch.charValue());   // 忽略其余 Ctrl 组合
                    }
                    default -> { /* 其余按键忽略 */ }
                }

                if (withMenu) {
                    menu = matches(buf);
                    if (sel >= menu.size()) sel = 0;
                }
                render(prompt, buf, menu, sel, withMenu);
            }
        } catch (IOException e) {
            log.error("读取输入失败", e);
            return null;
        }
    }

    /** 候选命令：仅在「以 / 开头、命令 token 尚未结束（无空格）」时给出。 */
    private List<Cmd> matches(CharSequence buf) {
        String s = buf.toString();
        if (s.isEmpty() || !s.startsWith("/") || s.contains(" ")) {
            return List.of();
        }
        return COMMANDS.stream().filter(c -> c.name().startsWith(s)).toList();
    }

    /**
     * 重画当前输入行 + 下方候选菜单 + 底部快捷键提示；用相对光标移动，兼容滚动（不占用全屏）。
     * 颜色码是不可见字符，不能计入光标列数 —— 列数一律用 plain 文本长度。
     */
    private void render(String prompt, CharSequence buf, List<Cmd> menu, int sel, boolean showHint) {
        StringBuilder sb = new StringBuilder();
        sb.append('\r').append(ESC).append('J');                    // 回到行首，清掉本行及下方（擦除旧菜单）
        sb.append(C_PROMPT).append(prompt).append(RESET);
        if (buf.length() == 0 && showHint) {
            sb.append(C_HINT).append(PLACEHOLDER).append(RESET);    // 空行占位提示
        } else {
            sb.append(colorBuf(buf));                               // 命令 token / 参数分色
            sb.append(ghost(buf, menu, sel));                       // fish 风格行内幽灵补全（光标之后）
        }
        for (int i = 0; i < menu.size(); i++) {
            Cmd c = menu.get(i);
            boolean on = i == sel;
            sb.append("\r\n")
                    .append(on ? C_BAR + "▌ " + RESET : "  ")                       // 选中行左侧色条
                    .append(highlightName(c.name(), buf.length(), on))
                    .append(" ".repeat(Math.max(1, NAME_WIDTH - c.name().length())))
                    .append(on ? C_DESC : C_DESC_DIM).append(c.desc()).append(RESET);
        }
        if (!menu.isEmpty()) {
            sb.append("\r\n").append(C_HINT).append("  ").append(FOOTER).append(RESET);
            sb.append(ESC).append(menu.size() + 1).append('A');     // 上移「候选行数 + 提示行」回到输入行
        }
        // 光标定位到输入点：列 = 提示符可见宽 + buf 可见宽（占位提示/幽灵补全都不是真实输入，不计入）
        sb.append('\r').append(ESC).append(prompt.length() + buf.length()).append('C');
        System.out.print(sb);
        System.out.flush();
    }

    /** 候选名着色：已输入的匹配前缀标琥珀，剩余部分按选中与否用亮青 / 暗青。 */
    private String highlightName(String name, int matchedLen, boolean selected) {
        int n = Math.min(matchedLen, name.length());
        return C_MATCH + name.substring(0, n) + RESET
                + (selected ? C_CMD : C_CMD_DIM) + name.substring(n) + RESET;
    }

    /** 行内幽灵补全：把选中候选相对已输入内容的剩余部分以极暗灰显示在光标后。 */
    private String ghost(CharSequence buf, List<Cmd> menu, int sel) {
        if (menu.isEmpty()) {
            return "";
        }
        String name = menu.get(sel).name();
        String typed = buf.toString();
        return name.length() > typed.length() && name.startsWith(typed)
                ? C_GHOST + name.substring(typed.length()) + RESET
                : "";
    }

    /** 给输入着色：命令 token（首个空格前、以 / 开头）标青，其后的参数标暖棕。 */
    private String colorBuf(CharSequence buf) {
        String s = buf.toString();
        if (!s.startsWith("/")) {
            return s;
        }
        int sp = s.indexOf(' ');
        return sp < 0
                ? C_CMD + s + RESET
                : C_CMD + s.substring(0, sp) + RESET + C_ARG + s.substring(sp) + RESET;
    }

    private void print(String s) {
        System.out.println(s == null ? "" : s);
    }

    /** raw 模式下把裸 {@code \n} 补成 {@code \r\n}，避免多行输出阶梯错位。 */
    private PrintStream crlfOut(PrintStream original) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                if (b == '\n') original.write('\r');
                original.write(b);
            }

            @Override
            public void flush() {
                original.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void printBanner() {
        StringBuilder sb = new StringBuilder("\n")
                .append(C_PROMPT).append("  easy-daily-report").append(RESET)
                .append(C_HINT).append("  ·  智能日报生成器").append(RESET).append("\n")
                .append(C_HINT).append("  ─────────────────────────────────────────────").append(RESET).append("\n");
        for (Cmd c : COMMANDS) {
            sb.append("  ").append(C_CMD).append(c.name()).append(RESET)
                    .append(" ".repeat(Math.max(1, NAME_WIDTH - c.name().length())))
                    .append(C_DESC_DIM).append(c.desc()).append(RESET).append('\n');
        }
        sb.append(C_HINT).append("  ").append(FOOTER).append(RESET).append('\n');
        System.out.println(sb);
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
