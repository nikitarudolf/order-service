package com.innowise.order_service.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Entity(name = "PurchaseOrder")
@Table(name = "orders")
@SQLRestriction("deleted = false")
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Embedded
    private UserSnapshot userSnapshot;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.NEW;

    @Setter
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    private boolean deleted;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(Long userId, UserSnapshot userSnapshot) {
        this.userId = userId;
        this.userSnapshot = userSnapshot;
    }

    public void updateItems(List<OrderItem> requestedItems) {
        Map<Long, Deque<OrderItem>> existingByItemId = new HashMap<>();
        items.stream()
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(item -> existingByItemId.computeIfAbsent(item.getItem().getId(), itemId -> new ArrayDeque<>())
                        .addLast(item));

        Set<OrderItem> retained = new HashSet<>();
        for (OrderItem requested : requestedItems) {
            Deque<OrderItem> matches = existingByItemId.get(requested.getItem().getId());
            OrderItem existing = matches == null ? null : matches.pollFirst();
            if (existing == null) {
                requested.setOrder(this);
                items.add(requested);
                retained.add(requested);
            } else {
                existing.setQuantity(requested.getQuantity());
                retained.add(existing);
            }
        }
        items.removeIf(item -> !retained.contains(item));
    }

    public void markDeleted() {
        deleted = true;
    }
}
