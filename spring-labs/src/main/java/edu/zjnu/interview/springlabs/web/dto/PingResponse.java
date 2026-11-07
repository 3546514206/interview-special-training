package edu.zjnu.interview.springlabs.web.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * GET /api/ping 响应体：{"status":"UP","service":"spring-labs","timestamp":"..."}
 */
@JsonPropertyOrder({"status", "service", "timestamp"})
public class PingResponse {

    private String status;
    private String service;
    private String timestamp;

    public PingResponse() {
    }

    public PingResponse(String status, String service, String timestamp) {
        this.status = status;
        this.service = service;
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
