package com.innowise.order_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record OrderResponse(OrderDetails order, @JsonInclude(JsonInclude.Include.ALWAYS) UserInfo user) {
}
