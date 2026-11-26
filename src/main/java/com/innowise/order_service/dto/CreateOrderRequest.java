package com.innowise.order_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid OrderItemRequest> items
) {
}
