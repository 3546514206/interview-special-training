package edu.zjnu.interview.springlabs.web.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * POST /api/echo 响应体：{"echo":"hello","length":5}
 */
@JsonPropertyOrder({"echo", "length"})
public class EchoResponse {

    private String echo;
    private int length;

    public EchoResponse() {
    }

    public EchoResponse(String echo, int length) {
        this.echo = echo;
        this.length = length;
    }

    public String getEcho() {
        return echo;
    }

    public void setEcho(String echo) {
        this.echo = echo;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }
}
