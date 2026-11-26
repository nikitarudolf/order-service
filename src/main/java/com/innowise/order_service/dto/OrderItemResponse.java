package com.innowise.order_service.dto;

public record OrderItemResponse(Long id, Long itemId, int quantity) {
}
