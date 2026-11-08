package edu.zjnu.interview.ai.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径沙箱回归：词法穿越、绝对路径、符号链接逃逸。
 */
class PathSandboxTest {

    @Test
    @DisplayName("沙箱内的相对路径可以解析")
    void resolvesValidPathInside(@TempDir Path root) throws IOException {
        Path resolved = PathSandbox.resolveInside(root, "reports/2024/q1.md");
        assertTrue(resolved.startsWith(root.toRealPath()));
    }

    @Test
    @DisplayName("../ 穿越被拒绝")
    void rejectsParentTraversal(@TempDir Path root) {
        assertThrows(PathSandbox.SandboxViolation.class,
                () -> PathSandbox.resolveInside(root, "../etc/passwd"));
        assertThrows(PathSandbox.SandboxViolation.class,
                () -> PathSandbox.resolveInside(root, "a/../../etc/passwd"));
    }

    @Test
    @DisplayName("指向沙箱外的绝对路径被拒绝")
    void rejectsAbsolutePathOutside(@TempDir Path root) {
        assertThrows(PathSandbox.SandboxViolation.class,
                () -> PathSandbox.resolveInside(root, "/etc/passwd"));
    }

    @Test
    @DisplayName("符号链接逃逸被拒绝（词法合法、物理越界）")
    void rejectsSymlinkEscape(@TempDir Path root) throws IOException {
        Path outside = Files.createTempDirectory("outside-sandbox-");
        try {
            Path secret = Files.writeString(outside.resolve("secret.txt"), "top secret");
            Path link = root.resolve("escape");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException e) {
                // 某些环境不允许创建符号链接，跳过该断言
                return;
            }
            assertThrows(PathSandbox.SandboxViolation.class,
                    () -> PathSandbox.resolveInside(root, "escape/secret.txt"),
                    "符号链接指向沙箱外时必须拒绝");
            assertTrue(Files.exists(secret));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    @DisplayName("沙箱内的符号链接是允许的")
    void allowsSymlinkInsideSandbox(@TempDir Path root) throws IOException {
        Path inner = Files.createDirectories(root.resolve("inner"));
        Files.writeString(inner.resolve("ok.txt"), "fine");
        try {
            Files.createSymbolicLink(root.resolve("link"), inner);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertDoesNotThrow(() -> PathSandbox.resolveInside(root, "link/ok.txt"));
    }

    @Test
    @DisplayName("空路径与空根目录一律拒绝")
    void rejectsEmptyInput(@TempDir Path root) {
        assertThrows(PathSandbox.SandboxViolation.class, () -> PathSandbox.resolveInside(root, ""));
        assertThrows(PathSandbox.SandboxViolation.class, () -> PathSandbox.resolveInside(null, "a.txt"));
    }

    @Test
    @DisplayName("tryResolve 不抛异常，越界返回空")
    void tryResolveReturnsEmptyOnViolation(@TempDir Path root) {
        PathSandbox sandbox = PathSandbox.of(root);
        assertTrue(sandbox.tryResolve("a.txt").isPresent());
        assertTrue(sandbox.tryResolve("../a.txt").isEmpty());
        assertFalse(sandbox.contains(root.getParent().resolve("a.txt")));
        assertTrue(sandbox.contains(root.resolve("a.txt")));
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
