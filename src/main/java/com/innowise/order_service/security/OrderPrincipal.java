package com.innowise.order_service.security;

public record OrderPrincipal(Long userId, boolean admin, String token) {

    @Override
    public String toString() {
        return "OrderPrincipal[userId=" + userId + ", admin=" + admin + "]";
    }
}
