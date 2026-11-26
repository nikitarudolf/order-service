package com.innowise.order_service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderItemRequest(@NotNull @Positive Long itemId, @Positive int quantity) {
}
