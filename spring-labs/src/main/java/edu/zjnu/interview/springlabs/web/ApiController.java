package edu.zjnu.interview.springlabs.web;

import edu.zjnu.interview.springlabs.web.dto.EchoRequest;
import edu.zjnu.interview.springlabs.web.dto.EchoResponse;
import edu.zjnu.interview.springlabs.web.dto.HelloResponse;
import edu.zjnu.interview.springlabs.web.dto.ItemDto;
import edu.zjnu.interview.springlabs.web.dto.PingResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试实验用的 HTTP 契约实现。
 *
 * <p>每个 handler 都是独立方法，方便在 IDEA 中直接打断点调试。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    /** /api/slow 的缺省睡眠时长（毫秒）。 */
    public static final int DEFAULT_SLOW_MS = 1000;

    /** /api/slow 单次请求允许的最大睡眠时长（毫秒）。 */
    public static final int MAX_SLOW_MS = 10000;

    /** /api/hello 缺省的 name 参数。 */
    public static final String DEFAULT_NAME = "world";

    /**
     * GET /api/ping → {"status":"UP","service":"spring-labs","timestamp":"<ISO-8601 本地时间>"}
     *
     * <p>timestamp 形如 2026-11-07T21:11:23.337（本地时间，毫秒精度）。
     */
    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("UP", "spring-labs", formatTimestamp(LocalDateTime.now()));
    }

    /**
     * timestamp 的固定格式：{@code yyyy-MM-dd'T'HH:mm:ss.SSS}。
     *
     * <p>刻意不用 {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}：它会丢掉毫秒的尾零
     * （{@code .530} → {@code .53}，{@code .000} → 干脆没有小数部分），
     * 导致同一字段的宽度随时钟变化，客户端正则断言会在毫秒末位为 0 时随机失败。
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    /**
     * 把本地时间格式化成固定宽度的 timestamp 字符串。
     *
     * @param time 本地时间（精度低于毫秒时向下截断）
     * @return 形如 {@code 2026-11-07T21:26:59.530} 的字符串
     */
    public static String formatTimestamp(LocalDateTime time) {
        return TIMESTAMP_FORMATTER.format(time.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * GET /api/hello?name=香农 → {"message":"你好, 香农!","name":"香农"}
     * GET /api/hello                → {"message":"你好, world!","name":"world"}
     */
    @GetMapping("/hello")
    public HelloResponse hello(@RequestParam(name = "name", defaultValue = DEFAULT_NAME) String name) {
        return new HelloResponse("你好, " + name + "!", name);
    }

    /**
     * POST /api/echo  body: {"message":"hello"} → {"echo":"hello","length":5}
     *
     * <p>message 为空时由 {@code @Valid} + {@code @NotBlank} 抛出校验异常，
     * 统一由 {@link GlobalExceptionHandler} 转成 400 {"error":"message 不能为空"}。
     */
    @PostMapping("/echo")
    public EchoResponse echo(@Valid @RequestBody EchoRequest request) {
        String message = request.getMessage();
        return new EchoResponse(message, message.length());
    }

    /**
     * GET /api/items → {"items":[{"id":1,"name":"alpha"},{"id":2,"name":"beta"},{"id":3,"name":"gamma"}]}
     */
    @GetMapping("/items")
    public Map<String, List<ItemDto>> items() {
        List<ItemDto> items = new ArrayList<>();
        items.add(new ItemDto(1, "alpha"));
        items.add(new ItemDto(2, "beta"));
        items.add(new ItemDto(3, "gamma"));

        Map<String, List<ItemDto>> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    /**
     * GET /api/slow?ms=1200 → 睡眠 ms 毫秒后返回 {"sleptMs":1200}
     *
     * <p>缺省 1000ms，上限 10000ms。内部使用 {@code Thread.sleep}，
     * 便于在断点挂起时观察线程栈（Tomcat 工作线程处于 TIMED_WAITING）。
     */
    @GetMapping("/slow")
    public Map<String, Integer> slow(@RequestParam(name = "ms", defaultValue = "" + DEFAULT_SLOW_MS) int ms) {
        int sleepMs = normalizeSleepMs(ms);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Integer> body = new LinkedHashMap<>();
        body.put("sleptMs", sleepMs);
        return body;
    }

    /**
     * GET /api/boom → 500 {"error":"boom"}
     *
     * <p>抛出运行时异常，由 {@link GlobalExceptionHandler} 统一转换为 500 响应体。
     */
    @GetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("boom");
    }

    /**
     * 计算实际睡眠时长：非法值回落到缺省值，超限则截断到上限。
     *
     * <p>抽成静态方法，便于单元测试直接断言边界值（无需真的睡 10 秒）。
     */
    public static int normalizeSleepMs(int ms) {
        if (ms <= 0) {
            return DEFAULT_SLOW_MS;
        }
        return Math.min(ms, MAX_SLOW_MS);
    }
}
