package edu.zjnu.interview.springlabs.web.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * GET /api/hello 响应体：{"message":"你好, 香农!","name":"香农"}
 */
@JsonPropertyOrder({"message", "name"})
public class HelloResponse {

    private String message;
    private String name;

    public HelloResponse() {
    }

    public HelloResponse(String message, String name) {
        this.message = message;
        this.name = name;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
