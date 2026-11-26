package com.innowise.order_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.innowise.order_service.entity.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateOrderRequest {

    private OrderStatus status;

    @Size(min = 1, max = 100)
    private List<@NotNull @Valid OrderItemRequest> items;

    public UpdateOrderRequest() {
    }

    public UpdateOrderRequest(OrderStatus status, List<OrderItemRequest> items) {
        this.status = status;
        this.items = items;
    }

    public OrderStatus status() {
        return status;
    }

    public List<OrderItemRequest> items() {
        return items;
    }

    @JsonSetter(value = "status", nulls = Nulls.FAIL)
    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    @JsonSetter(value = "items", nulls = Nulls.FAIL)
    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, JsonNode value) {
        throw new IllegalArgumentException("Unsupported order update field: " + name);
    }
}
