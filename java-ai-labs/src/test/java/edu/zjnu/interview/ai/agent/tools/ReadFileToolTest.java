package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件工具的沙箱边界。
 *
 * <p>这是「权限控制」最容易被面试官追问的地方：
 * 只做字符串前缀检查的实现，会被软链接、绝对路径、{@code ../} 三种方式绕过。
 */
class ReadFileToolTest {

    @TempDir
    Path sandbox;

    @TempDir
    Path outside;

    private ToolContext ctx() {
        return ToolContext.of("trace-test", sandbox);
    }

    private ToolResult read(String path) {
        return new ReadFileTool().execute(
                ToolCall.of("c1", ReadFileTool.NAME, "{\"path\":\"" + path + "\"}"), ctx());
    }

    @Test
    @DisplayName("沙箱内的文件可以正常读取")
    void readsFileInsideSandbox() throws IOException {
        Files.writeString(sandbox.resolve("note.txt"), "内部资料", StandardCharsets.UTF_8);

        ToolResult result = read("note.txt");

        assertTrue(result.success());
        assertTrue(result.content().contains("内部资料"));
    }

    @Test
    @DisplayName("相对路径穿越（../）被拒绝")
    void rejectsParentTraversal() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "机密", StandardCharsets.UTF_8);

        ToolResult result = read("../secret.txt");

        assertFalse(result.success());
        assertEquals("PATH_OUTSIDE_SANDBOX", result.errorCode());
    }

    @Test
    @DisplayName("多级穿越被拒绝")
    void rejectsDeepTraversal() {
        ToolResult result = read("a/b/../../../etc/passwd");

        assertFalse(result.success());
        assertEquals("PATH_OUTSIDE_SANDBOX", result.errorCode());
    }

    @Test
    @DisplayName("绝对路径越界被拒绝")
    void rejectsAbsolutePath() {
        ToolResult result = read("/etc/passwd");

        assertFalse(result.success());
        assertEquals("PATH_OUTSIDE_SANDBOX", result.errorCode());
    }

    @Test
    @DisplayName("软链接指向沙箱外同样被拒绝（真实路径二次校验）")
    void rejectsSymlinkEscape() throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "机密", StandardCharsets.UTF_8);
        Path link = sandbox.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 当前文件系统不支持软链接，跳过
        }

        ToolResult result = read("link.txt");

        assertFalse(result.success());
        assertEquals("PATH_OUTSIDE_SANDBOX", result.errorCode());
    }

    @Test
    @DisplayName("文件不存在 → FILE_NOT_FOUND")
    void fileNotFound() {
        ToolResult result = read("missing.txt");

        assertFalse(result.success());
        assertEquals("FILE_NOT_FOUND", result.errorCode());
    }

    @Test
    @DisplayName("目录不是文件 → NOT_A_FILE")
    void directoryIsNotAFile() throws IOException {
        Files.createDirectories(sandbox.resolve("subdir"));

        ToolResult result = read("subdir");

        assertFalse(result.success());
        assertEquals("NOT_A_FILE", result.errorCode());
    }

    @Test
    @DisplayName("超过大小上限的文件被拒绝（防止一次读取打爆内存）")
    void rejectsOversizedFile() throws IOException {
        Files.writeString(sandbox.resolve("big.txt"), "x".repeat(100), StandardCharsets.UTF_8);
        ReadFileTool small = new ReadFileTool(10);

        ToolResult result = small.execute(
                ToolCall.of("c1", ReadFileTool.NAME, "{\"path\":\"big.txt\"}"), ctx());

        assertFalse(result.success());
        assertEquals("FILE_TOO_LARGE", result.errorCode());
    }

    @Test
    @DisplayName("没有工作目录时拒绝访问文件系统")
    void requiresWorkingDirectory() {
        ToolResult result = new ReadFileTool().execute(
                ToolCall.of("c1", ReadFileTool.NAME, "{\"path\":\"note.txt\"}"),
                ToolContext.of("trace-test", null));

        assertFalse(result.success());
        assertEquals("NO_WORKING_DIRECTORY", result.errorCode());
    }
}
