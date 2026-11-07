package edu.zjnu.interview.springlabs.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/echo 请求体：{"message":"hello"}
 *
 * <p>message 为空（null / "" / 纯空白）时由 @Valid 触发校验失败，
 * 由 GlobalExceptionHandler 统一转换为 400 {"error":"message 不能为空"}。
 */
public class EchoRequest {

    @NotBlank(message = "message 不能为空")
    private String message;

    public EchoRequest() {
    }

    public EchoRequest(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
