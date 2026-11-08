package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 当前时间工具。
 *
 * <p><b>为什么要把 Clock 做成可注入的</b>：Agent 的行为依赖时间（超时、TTL、时区），
 * 如果时间源写死为 {@code Instant.now()}，测试就没法确定性地断言，
 * 也会出现「昨天还绿的测试今天红了」的偶发失败。
 * 所以这里统一用注入的 {@link Clock}：生产用系统时钟，测试用 {@code Clock.fixed}。
 *
 * <p>默认时区为 UTC（可由参数覆盖），避免「服务器时区变了，输出就变了」的隐性行为。
 */
public final class CurrentTimeTool implements Tool {

    public static final String NAME = "current_time";

    private final Clock clock;

    public CurrentTimeTool() {
        this(Clock.systemUTC());
    }

    public CurrentTimeTool(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder(NAME, "获取当前时间，可指定时区与输出格式")
                .optional("zone", "string", "时区 ID，如 Asia/Shanghai，默认 UTC", "UTC")
                .optional("format", "string", "时间格式（DateTimeFormatter 模式），默认 ISO-8601", "ISO")
                .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        String zoneId = args.get("zone") == null ? "UTC" : String.valueOf(args.get("zone")).strip();
        String format = args.get("format") == null ? "ISO" : String.valueOf(args.get("format")).strip();

        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId.isEmpty() ? "UTC" : zoneId);
        } catch (Exception e) {
            return ToolResult.error("INVALID_ZONE", "非法时区: " + zoneId);
        }

        Instant now = clock.instant();
        ZonedDateTime zoned = now.atZone(zone);
        String text;
        if (format.isEmpty() || "ISO".equalsIgnoreCase(format)) {
            text = zoned.toOffsetDateTime().toString();
        } else {
            try {
                text = zoned.format(DateTimeFormatter.ofPattern(format));
            } catch (Exception e) {
                return ToolResult.error("INVALID_FORMAT", "非法时间格式: " + format);
            }
        }
        return ToolResult.ok(text, Map.of(
                "instant", now.toString(),
                "zone", zone.getId(),
                "formatted", text));
    }
}
