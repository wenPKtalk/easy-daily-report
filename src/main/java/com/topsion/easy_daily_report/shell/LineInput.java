package com.topsion.easy_daily_report.shell;

/** 读取一行输入的抽象，解耦调用方与具体终端库（jline / Lanterna）。 */
@FunctionalInterface
public interface LineInput {

    /**
     * @param prompt 提示符
     * @return 用户输入的一行；EOF / Ctrl+D / 退出时返回 {@code null}
     */
    String readLine(String prompt);
}
