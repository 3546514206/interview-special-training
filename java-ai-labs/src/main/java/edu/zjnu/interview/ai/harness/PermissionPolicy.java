package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.ToolAuthorizer;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具权限策略：白名单 + 读写分离 + 网络类需审批 + 路径沙箱。
 *
 * <p><b>核心思想：授权发生在执行之前，且拒绝理由必须回灌给模型</b>
 * （这正是 {@link ToolAuthorizer} 的 SPI 约定）。
 * 模型拿到「读操作被拒」才会去换一条路；拿到空指针或 500，它只会重试或胡言乱语。
 *
 * <p><b>四道关卡，按「先便宜后昂贵」排序</b>：
 * <ol>
 *   <li><b>白名单</b>：不在白名单里的工具直接拒绝。没在白名单里说明「没授权」，
 *       而不是「暂时没找到」——安全策略里这两种语义的默认值必须相反。</li>
 *   <li><b>读写分离</b>：{@code READ_ONLY} 默认可用；{@code WRITE} 必须显式授予。
 *       理由：写操作不可回滚，把「读」和「写」放在同一个开关后面，
 *       等于让一次配置失误就具备破坏性。</li>
 *   <li><b>审批</b>：{@code NETWORK} 类工具（以及显式声明 {@code requiresApproval} 的工具）
 *       必须拿到批准，默认拒绝。网络是「数据出境」的高风险动作。</li>
 *   <li><b>路径沙箱</b>：工具参数里的路径必须落在沙箱内（见 {@link PathSandbox}）。</li>
 * </ol>
 *
 * <p>四道关卡<b>全部</b>通过才放行，任意一道不通过就拒绝并给出原因。
 */
public final class PermissionPolicy implements ToolAuthorizer {

    /** 一次授权判定记录（审计用）。 */
    public record AuditRecord(String toolName, boolean allowed, String reason, long timestampMillis) {
    }

    private static final Set<String> DEFAULT_PATH_ARGUMENTS = Set.of(
            "path", "file", "filepath", "file_path", "filename", "dir", "directory",
            "folder", "target", "src", "dst", "source", "destination");

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private final Set<String> allowedTools = new LinkedHashSet<>();
    private final Set<String> writeTools = new LinkedHashSet<>();
    private final ApprovalHandler approval;
    private final PathSandbox sandbox;
    private final Set<String> pathArguments;
    private final List<AuditRecord> audits = new ArrayList<>();

    private PermissionPolicy(Builder builder) {
        this.specs.putAll(builder.specs);
        this.allowedTools.addAll(builder.allowedTools);
        this.writeTools.addAll(builder.writeTools);
        this.approval = builder.approval == null ? ApprovalHandler.denyByDefault() : builder.approval;
        this.sandbox = builder.sandbox;
        this.pathArguments = builder.pathArguments;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String authorize(ToolCall call, ToolContext ctx) {
        if (call == null) {
            return audit("null", false, "调用为空");
        }
        String name = call.name();
        ToolSpec spec = specs.get(name);
        if (spec == null) {
            // 未知工具 = 未授权。模型凭空造一个工具名来调用是最常见的越权尝试。
            return audit(name, false, "工具未注册，拒绝执行: " + name);
        }
        if (!allowedTools.contains(name)) {
            return audit(name, false, "工具不在白名单内，拒绝执行: " + name);
        }
        if (spec.sideEffect() == ToolSpec.SideEffect.WRITE && !writeTools.contains(name)) {
            return audit(name, false, "写操作未显式授权，拒绝执行: " + name);
        }
        if (spec.sideEffect() == ToolSpec.SideEffect.NETWORK || spec.requiresApproval()) {
            ApprovalHandler.Approval approvalResult = approval.decide(name, call, ctx);
            if (approvalResult == null || !approvalResult.approved()) {
                String reason = approvalResult == null ? "审批无响应" : approvalResult.reason();
                return audit(name, false, "审批未通过: " + reason);
            }
        }
        String sandboxReason = checkPaths(call, ctx);
        if (sandboxReason != null) {
            return audit(name, false, sandboxReason);
        }
        return audit(name, true, "允许执行");
    }

    /**
     * 校验工具参数里的路径。
     *
     * @return null 表示通过；非 null 为拒绝理由
     */
    private String checkPaths(ToolCall call, ToolContext ctx) {
        Path root = sandboxRoot(ctx);
        if (root == null) {
            // 没有沙箱根：不拦截路径（工具本身可能不涉及文件）；
            // 但只要参数里出现路径，说明工具会碰文件系统，却没有沙箱 → 直接拒绝更安全。
            if (hasPathArgument(call)) {
                return "工具涉及路径操作但未配置路径沙箱，拒绝执行: " + call.name();
            }
            return null;
        }
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (!isPathArgument(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            List<String> candidates = new ArrayList<>();
            if (value instanceof String s) {
                candidates.add(s);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        candidates.add(s);
                    }
                }
            }
            for (String candidate : candidates) {
                try {
                    PathSandbox.resolveInside(root, candidate);
                } catch (PathSandbox.SandboxViolation e) {
                    return "路径校验失败: " + e.getMessage();
                }
            }
        }
        return null;
    }

    private Path sandboxRoot(ToolContext ctx) {
        if (sandbox != null) {
            return sandbox.root();
        }
        if (ctx != null && ctx.workingDirectory() != null) {
            return ctx.workingDirectory();
        }
        return null;
    }

    private boolean hasPathArgument(ToolCall call) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        for (String key : args.keySet()) {
            if (isPathArgument(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPathArgument(String key) {
        return key != null && pathArguments.contains(key.toLowerCase(Locale.ROOT));
    }

    private String audit(String toolName, boolean allowed, String reason) {
        audits.add(new AuditRecord(toolName, allowed, reason, System.currentTimeMillis()));
        return allowed ? null : reason;
    }

    /** 授权审计记录。 */
    public List<AuditRecord> audits() {
        return List.copyOf(audits);
    }

    /** 已注册的工具。 */
    public Map<String, ToolSpec> specs() {
        return Map.copyOf(specs);
    }

    /** 构造器。 */
    public static final class Builder {
        private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
        private final Set<String> allowedTools = new LinkedHashSet<>();
        private final Set<String> writeTools = new LinkedHashSet<>();
        private ApprovalHandler approval;
        private PathSandbox sandbox;
        private Set<String> pathArguments = DEFAULT_PATH_ARGUMENTS;

        /** 注册并放行一个工具（按其声明的副作用等级生效）。 */
        public Builder allow(ToolSpec spec) {
            specs.put(spec.name(), spec);
            allowedTools.add(spec.name());
            return this;
        }

        /** 注册并放行一个写工具（显式授予写权限）。 */
        public Builder allowWrite(ToolSpec spec) {
            allow(spec);
            writeTools.add(spec.name());
            return this;
        }

        /** 显式拒绝某个工具名（即使之前 allow 过）。 */
        public Builder deny(String toolName) {
            allowedTools.remove(toolName);
            return this;
        }

        /** 注册工具但不放行（用于「知道它存在但不允许调用」）。 */
        public Builder register(ToolSpec spec) {
            specs.put(spec.name(), spec);
            return this;
        }

        /** 审批回调。 */
        public Builder approvalHandler(ApprovalHandler value) {
            this.approval = value;
            return this;
        }

        /** 路径沙箱。 */
        public Builder sandbox(PathSandbox value) {
            this.sandbox = value;
            return this;
        }

        /** 自定义被视为路径的参数名。 */
        public Builder pathArguments(String... names) {
            Set<String> set = new LinkedHashSet<>();
            for (String name : names) {
                set.add(name.toLowerCase(Locale.ROOT));
            }
            this.pathArguments = set;
            return this;
        }

        public PermissionPolicy build() {
            return new PermissionPolicy(this);
        }
    }
}
