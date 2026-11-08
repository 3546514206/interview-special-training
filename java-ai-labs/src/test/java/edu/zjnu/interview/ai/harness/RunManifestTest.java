package edu.zjnu.interview.ai.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行清单回归：可归档、可回读、指纹稳定。
 */
class RunManifestTest {

    @Test
    @DisplayName("清单可写入并回读，字段不丢")
    void writesAndReadsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/run-manifest.json");
        RunManifest manifest = RunManifest.builder()
                .runId("run-001")
                .createdAt("2024-11-08T10:00:00Z")
                .model("qwen-plus", "2024-11-01", Map.of("temperature", 0.2))
                .config("budget_usd", 0.01)
                .randomSeed(42L)
                .notes("实验说明")
                .build();

        manifest.writeTo(file);
        assertTrue(Files.exists(file));

        RunManifest reloaded = RunManifest.read(file);
        assertEquals("run-001", reloaded.runId());
        assertEquals("2024-11-08T10:00:00Z", reloaded.createdAt());
        assertEquals(1, reloaded.models().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> plus = (Map<String, Object>) reloaded.models().get("qwen-plus");
        assertEquals("qwen-plus", plus.get("name"));
        assertEquals("2024-11-01", plus.get("version"));
        assertEquals(0.01, (Double) reloaded.config().get("budget_usd"), 1e-9);
        assertEquals(42L, reloaded.randomSeed());
        assertEquals("实验说明", reloaded.notes());
    }

    @Test
    @DisplayName("目录指纹稳定；内容变化后指纹变化")
    void fingerprintIsStableAndSensitiveToContent(@TempDir Path dir) throws IOException {
        Path code = Files.createDirectories(dir.resolve("code"));
        Files.writeString(code.resolve("A.java"), "class A {}");
        Files.writeString(code.resolve("B.java"), "class B {}");

        String first = RunManifest.fingerprintOf(code);
        String second = RunManifest.fingerprintOf(code);
        assertEquals(first, second, "相同内容必须得到相同指纹");
        assertEquals(64, first.length(), "SHA-256 十六进制长度");

        Files.writeString(code.resolve("B.java"), "class B { int x; }");
        String changed = RunManifest.fingerprintOf(code);
        assertNotEquals(first, changed, "内容变化必须导致指纹变化");
    }

    @Test
    @DisplayName("清单里可以记录语料与代码指纹")
    void recordsFingerprints(@TempDir Path dir) throws IOException {
        Path corpus = Files.createDirectories(dir.resolve("corpus"));
        Files.writeString(corpus.resolve("doc1.md"), "蚂蚁财富的货币乘数");

        RunManifest manifest = RunManifest.builder()
                .runId("run-002")
                .fingerprintOf("corpus", corpus)
                .build();
        assertEquals(1, manifest.fingerprints().size());
        assertTrue(manifest.fingerprints().get("corpus").length() == 64);
    }

    @Test
    @DisplayName("不存在的路径得到空指纹而不是异常")
    void handlesMissingPath(@TempDir Path dir) throws IOException {
        assertEquals("", RunManifest.fingerprintOf(dir.resolve("not-exist")));
    }
}
