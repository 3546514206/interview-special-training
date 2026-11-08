package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件读取工具（带工作目录沙箱）。
 *
 * <p><b>沙箱模型</b>：以 {@link ToolContext#workingDirectory()} 为根，
 * 模型给出的路径必须规范化（normalize）之后仍然落在根目录内，否则拒绝。
 * 两条防线：
 * <ol>
 *   <li><b>字符串级</b>：{@code root.resolve(p).normalize().startsWith(root)}，
 *       拦掉 {@code ../} 穿越与绝对路径；</li>
 *   <li><b>真实路径级</b>：文件存在时用 {@code toRealPath()} 再比一次，
 *       拦掉「根目录里的软链接指向根外」这种绕过。软链接绕过是文件沙箱最经典的漏洞，
 *       只做字符串检查的实现基本都会被渗透测试打穿。</li>
 * </ol>
 *
 * <p>另外还有<b>大小上限</b>：模型可能请求读一个 2GB 的日志文件，
 * 一次读取就能把 Agent 进程的内存打爆。限制大小是工具层最基本的自我保护。
 *
 * <p>本工具只读不写，副作用等级 {@code READ_ONLY}；
 * 若要提供写能力，必须额外声明 {@code WRITE} 副作用并接入审批策略——
 * <b>副作用等级声明错了，权限系统就形同虚设</b>。
 */
public final class ReadFileTool implements Tool {

    public static final String NAME = "read_file";

    /** 默认最大读取字节数。 */
    public static final int DEFAULT_MAX_BYTES = 64 * 1024;

    private final int maxBytes;

    public ReadFileTool() {
        this(DEFAULT_MAX_BYTES);
    }

    public ReadFileTool(int maxBytes) {
        this.maxBytes = Math.max(1, maxBytes);
    }

    public int maxBytes() {
        return maxBytes;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder(NAME, "读取工作目录内的文本文件内容（只读，受沙箱与大小限制）")
                .param("path", "string", true, "相对于工作目录的文件路径")
                .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        Object rawPath = args.get("path");
        if (rawPath == null) {
            return ToolResult.error("MISSING_ARGUMENT", "缺少参数 path");
        }
        String raw = String.valueOf(rawPath).strip();
        if (raw.isEmpty()) {
            return ToolResult.error("EMPTY_PATH", "文件路径为空");
        }
        Path root = ctx == null ? null : ctx.workingDirectory();
        if (root == null) {
            return ToolResult.error("NO_WORKING_DIRECTORY", "执行上下文没有提供工作目录，拒绝访问文件系统");
        }
        Path base;
        try {
            base = root.toAbsolutePath().normalize();
        } catch (Exception e) {
            return ToolResult.error("INVALID_WORKING_DIRECTORY", "工作目录非法: " + root);
        }

        Path target;
        try {
            // resolve 对绝对路径会直接返回绝对路径，随后 normalize + startsWith 判定即可拦下
            target = base.resolve(raw).normalize();
        } catch (InvalidPathException e) {
            return ToolResult.error("INVALID_PATH", "非法路径: " + raw);
        }

        if (!target.startsWith(base)) {
            return ToolResult.error("PATH_OUTSIDE_SANDBOX",
                    "路径越界：'" + raw + "' 规范化后落在沙箱 [" + base + "] 之外");
        }
        if (target.equals(base)) {
            return ToolResult.error("PATH_OUTSIDE_SANDBOX", "不能读取沙箱根目录本身");
        }
        if (!Files.exists(target)) {
            return ToolResult.error("FILE_NOT_FOUND", "文件不存在: " + raw);
        }
        if (Files.isDirectory(target)) {
            return ToolResult.error("NOT_A_FILE", "目标是目录而非文件: " + raw);
        }

        // 第二道防线：真实路径（解析软链接）后必须仍在沙箱内
        try {
            Path realRoot = base.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                return ToolResult.error("PATH_OUTSIDE_SANDBOX",
                        "路径越界（经软链接解析后）：'" + raw + "'");
            }
        } catch (IOException e) {
            return ToolResult.error("IO_ERROR", "无法解析真实路径: " + e.getMessage());
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return ToolResult.error("IO_ERROR", "无法读取文件大小: " + e.getMessage());
        }
        if (size > maxBytes) {
            return ToolResult.error("FILE_TOO_LARGE",
                    "文件大小 " + size + " 字节超过上限 " + maxBytes + " 字节");
        }

        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return ToolResult.ok(content, Map.of(
                    "path", base.relativize(target).toString(),
                    "bytes", size));
        } catch (IOException e) {
            return ToolResult.error("IO_ERROR", "读取失败: " + e.getMessage());
        }
    }
}
