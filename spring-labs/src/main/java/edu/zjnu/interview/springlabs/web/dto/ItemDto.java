package edu.zjnu.interview.springlabs.web.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * GET /api/items 中的单个元素：{"id":1,"name":"alpha"}
 */
@JsonPropertyOrder({"id", "name"})
public class ItemDto {

    private int id;
    private String name;

    public ItemDto() {
    }

    public ItemDto(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
