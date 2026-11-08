package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限策略回归：白名单、读写分离、网络审批、路径沙箱。
 */
class PermissionPolicyTest {

    private static final ToolSpec READ_TOOL = ToolSpec.builder("read_report", "读取报告")
            .param("path", "string", true, "文件相对路径")
            .sideEffect(ToolSpec.SideEffect.READ_ONLY)
            .build();

    private static final ToolSpec WRITE_TOOL = ToolSpec.builder("write_report", "写入报告")
            .param("path", "string", true, "文件相对路径")
            .sideEffect(ToolSpec.SideEffect.WRITE)
            .build();

    private static final ToolSpec NETWORK_TOOL = ToolSpec.builder("fetch_url", "访问外网")
            .param("url", "string", true, "地址")
            .sideEffect(ToolSpec.SideEffect.NETWORK)
            .requiresApproval(true)
            .build();

    @Test
    @DisplayName("未注册的工具一律拒绝（未知 = 未授权）")
    void deniesUnknownTool() {
        PermissionPolicy policy = PermissionPolicy.builder().allow(READ_TOOL).build();
        String reason = policy.authorize(new ToolCall("c1", "rm_rf", "{\"path\":\"a\"}"), ToolContext.of("t", null));
        assertNotNull(reason);
        assertTrue(reason.contains("未注册"));
    }

    @Test
    @DisplayName("不在白名单里的工具被拒绝")
    void deniesToolOutsideWhitelist() {
        PermissionPolicy policy = PermissionPolicy.builder().register(READ_TOOL).build();
        String reason = policy.authorize(new ToolCall("c1", "read_report", "{\"path\":\"a.txt\"}"),
                ToolContext.of("t", null));
        assertNotNull(reason);
        assertTrue(reason.contains("白名单"));
    }

    @Test
    @DisplayName("读操作放行")
    void allowsReadOnlyTool() {
        PermissionPolicy policy = PermissionPolicy.builder().allow(READ_TOOL).build();
        assertNull(policy.authorize(new ToolCall("c1", "read_report", "{}"), ToolContext.of("t", null)));
    }

    @Test
    @DisplayName("写操作必须显式授权（读写分离）")
    void writeRequiresExplicitGrant() {
        PermissionPolicy readOnly = PermissionPolicy.builder().allow(WRITE_TOOL).build();
        String reason = readOnly.authorize(new ToolCall("c1", "write_report", "{}"), ToolContext.of("t", null));
        assertNotNull(reason, "写工具未显式授权时必须拒绝");
        assertTrue(reason.contains("写操作"));

        PermissionPolicy withGrant = PermissionPolicy.builder().allowWrite(WRITE_TOOL).build();
        assertNull(withGrant.authorize(new ToolCall("c1", "write_report", "{}"), ToolContext.of("t", null)));
    }

    @Test
    @DisplayName("网络类工具默认拒绝；配置审批后才可能放行")
    void networkToolRequiresApproval() {
        PermissionPolicy denied = PermissionPolicy.builder()
                .allow(NETWORK_TOOL)
                .approvalHandler(ApprovalHandler.denyByDefault())
                .build();
        String reason = denied.authorize(new ToolCall("c1", "fetch_url", "{\"url\":\"https://example.com\"}"),
                ToolContext.of("t", null));
        assertNotNull(reason);
        assertTrue(reason.contains("审批"));

        PermissionPolicy allowed = PermissionPolicy.builder()
                .allow(NETWORK_TOOL)
                .approvalHandler(ApprovalHandler.allowAll())
                .build();
        assertNull(allowed.authorize(new ToolCall("c1", "fetch_url", "{\"url\":\"https://example.com\"}"),
                ToolContext.of("t", null)));
    }

    @Test
    @DisplayName("路径越界的工具调用被拒绝")
    void rejectsPathEscape(@TempDir Path root) {
        PermissionPolicy policy = PermissionPolicy.builder()
                .allow(READ_TOOL)
                .sandbox(PathSandbox.of(root))
                .build();
        ToolContext ctx = ToolContext.of("t", root);
        String reason = policy.authorize(new ToolCall("c1", "read_report", "{\"path\":\"../../etc/passwd\"}"), ctx);
        assertNotNull(reason);
        assertTrue(reason.contains("路径"));
        assertNull(policy.authorize(new ToolCall("c2", "read_report", "{\"path\":\"ok.txt\"}"), ctx));
    }

    @Test
    @DisplayName("涉及路径但没有配置沙箱时拒绝执行")
    void deniesPathToolWithoutSandbox() {
        PermissionPolicy policy = PermissionPolicy.builder().allow(READ_TOOL).build();
        String reason = policy.authorize(new ToolCall("c1", "read_report", "{\"path\":\"a.txt\"}"),
                ToolContext.of("t", null));
        assertNotNull(reason);
        assertTrue(reason.contains("沙箱"));
    }

    @Test
    @DisplayName("每次判定都留下审计记录")
    void recordsAuditTrail() {
        PermissionPolicy policy = PermissionPolicy.builder().allow(READ_TOOL).build();
        policy.authorize(new ToolCall("c1", "read_report", "{}"), ToolContext.of("t", null));
        policy.authorize(new ToolCall("c2", "rm_rf", "{}"), ToolContext.of("t", null));
        assertTrue(policy.audits().size() >= 2);
        assertTrue(policy.audits().stream().anyMatch(a -> !a.allowed()));
    }
}
