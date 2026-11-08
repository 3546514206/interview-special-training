package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会按剧本失败的模型：前 N 次返回指定分类的失败，之后成功。
 *
 * <p><b>为什么要专门造一个「会失败」的模型</b>：
 * 稳定性策略（重试 / 降级 / 熔断）最怕的就是「测不出来」——
 * 正常路径永远走不到失败分支，代码里的重试逻辑就成了永不执行的死代码。
 * 用一个可精确控制失败次数与失败分类的模型，就能把每一个分支都钉死。
 *
 * <p>完全确定性：失败次数、失败分类、成功后的响应都是构造时指定的，不含随机性。
 */
public final class FlakyChatModel implements ChatModel {

    private final String name;
    private final int failures;
    private final FailureCategory category;
    private final int statusCode;
    private final ChatResponse success;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> requestedModels = new CopyOnWriteArrayList<>();

    public FlakyChatModel(String name, int failures, FailureCategory category, ChatResponse success) {
        this(name, failures, category, statusCodeOf(category), success);
    }

    public FlakyChatModel(String name, int failures, FailureCategory category, int statusCode, ChatResponse success) {
        this.name = name;
        this.failures = failures;
        this.category = category == null ? FailureCategory.UNKNOWN : category;
        this.statusCode = statusCode;
        this.success = success == null ? ChatResponse.text("（默认成功响应）") : success;
    }

    private static int statusCodeOf(FailureCategory category) {
        switch (category) {
            case RATE_LIMIT:
                return 429;
            case TIMEOUT:
                return 504;
            case SERVER_ERROR:
                return 500;
            case CLIENT_ERROR:
                return 400;
            default:
                return 0;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int attempt = calls.incrementAndGet();
        requestedModels.add(request.model() == null ? "" : request.model());
        if (attempt <= failures) {
            String payload = ModelErrors.payload(category, statusCode, "模拟第 " + attempt + " 次失败");
            return new ChatResponse(payload, List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L);
        }
        return success;
    }

    /** 已调用次数。 */
    public int callCount() {
        return calls.get();
    }

    /** 每次调用请求里的模型名（用来断言「降级后确实换了模型」）。 */
    public List<String> requestedModels() {
        return List.copyOf(requestedModels);
    }
}
