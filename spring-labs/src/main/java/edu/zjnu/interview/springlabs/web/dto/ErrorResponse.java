package edu.zjnu.interview.springlabs.web.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 统一错误响应体：{"error":"<msg>"}
 */
@JsonPropertyOrder({"error"})
public class ErrorResponse {

    private String error;

    public ErrorResponse() {
    }

    public ErrorResponse(String error) {
        this.error = error;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
