package com.innowise.order_service.repository.specification;

import com.innowise.order_service.entity.Order;
import com.innowise.order_service.entity.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> hasUserId(Long userId) {
        return (root, query, builder) -> userId == null ? null : builder.equal(root.get("userId"), userId);
    }

    public static Specification<Order> hasStatuses(Set<OrderStatus> statuses) {
        return (root, query, builder) -> statuses == null || statuses.isEmpty()
                ? null : root.get("status").in(statuses);
    }

    public static Specification<Order> createdFrom(LocalDate from) {
        return (root, query, builder) -> from == null ? null
                : builder.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from.atStartOfDay());
    }

    public static Specification<Order> createdThrough(LocalDate to) {
        return (root, query, builder) -> to == null ? null
                : builder.lessThan(root.<LocalDateTime>get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
