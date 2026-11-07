package edu.zjnu.interview.springlabs;

import edu.zjnu.interview.springlabs.web.ApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖 HTTP 契约中 7 个接口的关键断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** GET /api/ping */
    @Test
    void pingReturnsUp() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("spring-labs"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(not(is(""))))
                // ISO-8601 本地时间：yyyy-MM-dd'T'HH:mm:ss.SSS（固定 3 位毫秒）
                .andExpect(jsonPath("$.timestamp").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")));
    }

    /**
     * 回归防护：timestamp 的毫秒必须是固定 3 位。
     *
     * <p>早期实现用 {@code ISO_LOCAL_DATE_TIME} 序列化，会吞掉毫秒尾零
     * （{@code .530} → {@code .53}，{@code .000} → 没有小数部分），
     * 使上面的正则断言在毫秒末位为 0 时随机失败。
     */
    @Test
    void timestampKeepsFixedMillisecondWidth() {
        assertThat(ApiController.formatTimestamp(LocalDateTime.of(2026, 11, 7, 21, 26, 59, 530_000_000)),
                is("2026-11-07T21:26:59.530"));
        assertThat(ApiController.formatTimestamp(LocalDateTime.of(2026, 11, 7, 21, 26, 59, 5_000_000)),
                is("2026-11-07T21:26:59.005"));
        assertThat(ApiController.formatTimestamp(LocalDateTime.of(2026, 11, 7, 21, 26, 59, 0)),
                is("2026-11-07T21:26:59.000"));
    }

    /** GET /api/hello?name=香农 */
    @Test
    void helloWithName() throws Exception {
        mockMvc.perform(get("/api/hello").param("name", "香农"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("你好, 香农!"))
                .andExpect(jsonPath("$.name").value("香农"));
    }

    /** GET /api/hello（缺省 name） */
    @Test
    void helloDefaultsToWorld() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("你好, world!"))
                .andExpect(jsonPath("$.name").value("world"));
    }

    /** POST /api/echo 正常路径 */
    @Test
    void echoReturnsMessageAndLength() throws Exception {
        mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.echo").value("hello"))
                .andExpect(jsonPath("$.length").value(5));
    }

    /** POST /api/echo message 为空 */
    @Test
    void echoRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message 不能为空"));
    }

    /** POST /api/echo 请求体缺失 */
    @Test
    void echoRejectsMissingBody() throws Exception {
        mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message 不能为空"));
    }

    /** GET /api/items */
    @Test
    void itemsReturnsFixedList() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].name").value("alpha"))
                .andExpect(jsonPath("$.items[1].id").value(2))
                .andExpect(jsonPath("$.items[1].name").value("beta"))
                .andExpect(jsonPath("$.items[2].id").value(3))
                .andExpect(jsonPath("$.items[2].name").value("gamma"));
    }

    /** GET /api/slow?ms=1200 */
    @Test
    void slowSleepsRequestedMilliseconds() throws Exception {
        long start = System.nanoTime();
        mockMvc.perform(get("/api/slow").param("ms", "1200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sleptMs").value(1200));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        org.junit.jupiter.api.Assertions.assertTrue(elapsedMs >= 1200,
                "期望至少休眠 1200ms，实际 " + elapsedMs + "ms");
    }

    /** GET /api/slow（缺省 ms） */
    @Test
    void slowDefaultsToOneSecond() throws Exception {
        mockMvc.perform(get("/api/slow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sleptMs").value(1000));
    }

    /** GET /api/slow 超过上限时被截断、非法值回落到缺省值 */
    @Test
    void slowSleepDurationIsNormalized() {
        org.junit.jupiter.api.Assertions.assertEquals(10000,
                edu.zjnu.interview.springlabs.web.ApiController.normalizeSleepMs(99999));
        org.junit.jupiter.api.Assertions.assertEquals(10000,
                edu.zjnu.interview.springlabs.web.ApiController.normalizeSleepMs(10000));
        org.junit.jupiter.api.Assertions.assertEquals(1000,
                edu.zjnu.interview.springlabs.web.ApiController.normalizeSleepMs(0));
        org.junit.jupiter.api.Assertions.assertEquals(1000,
                edu.zjnu.interview.springlabs.web.ApiController.normalizeSleepMs(-5));
        org.junit.jupiter.api.Assertions.assertEquals(1200,
                edu.zjnu.interview.springlabs.web.ApiController.normalizeSleepMs(1200));
    }

    /** GET /api/boom */
    @Test
    void boomReturnsServerError() throws Exception {
        mockMvc.perform(get("/api/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("boom"));
    }

    /** GET /actuator/health */
    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** 未匹配的路径返回 404 而不是 500 */
    @Test
    void unknownPathReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(notNullValue()));
    }

    /** 请求方法不支持返回 405，而不是被兜底分支吞成 500 */
    @Test
    void wrongMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value(notNullValue()));
    }

    /** 查询参数类型错误返回 400 */
    @Test
    void badQueryParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/slow").param("ms", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(notNullValue()));
    }
}
