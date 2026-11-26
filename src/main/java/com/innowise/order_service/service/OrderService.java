package com.innowise.order_service.service;

import com.innowise.order_service.client.UserServiceClient;
import com.innowise.order_service.dto.CreateOrderRequest;
import com.innowise.order_service.dto.OrderItemRequest;
import com.innowise.order_service.dto.OrderResponse;
import com.innowise.order_service.dto.UpdateOrderRequest;
import com.innowise.order_service.dto.UserInfo;
import com.innowise.order_service.entity.Item;
import com.innowise.order_service.entity.Order;
import com.innowise.order_service.entity.OrderItem;
import com.innowise.order_service.entity.OrderStatus;
import com.innowise.order_service.mapper.OrderMapper;
import com.innowise.order_service.repository.ItemRepository;
import com.innowise.order_service.repository.OrderRepository;
import com.innowise.order_service.repository.specification.OrderSpecifications;
import com.innowise.order_service.security.OrderPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private static final BigDecimal TOTAL_PRICE_LIMIT = new BigDecimal("10000000000");
    private static final Set<String> SORT_FIELDS =
            Set.of("id", "userId", "status", "totalPrice", "createdAt", "updatedAt");

    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final OrderMapper orderMapper;
    private final UserServiceClient userServiceClient;
    private final AuditingHandler auditingHandler;

    @Transactional
    public OrderResponse create(CreateOrderRequest request, OrderPrincipal caller) {
        UserInfo user = userServiceClient.getByEmail(request.email(), caller.token());
        requireAccess(user.id(), caller);

        Order order = new Order(user.id(), orderMapper.toSnapshot(user));
        setItems(order, request.items());
        return orderMapper.toResponse(orderRepository.saveAndFlush(order));
    }

    public OrderResponse getById(Long id, OrderPrincipal caller) {
        Order order = orderRepository.findById(id).orElseThrow(() -> notFound(id));
        requireAccess(order.getUserId(), caller);
        return orderMapper.toResponse(order);
    }

    public Page<OrderResponse> getAll(LocalDate from, LocalDate to, Set<OrderStatus> statuses,
                                      Pageable pageable, OrderPrincipal caller) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "createdFrom must be on or before createdTo");
        }
        Long userId = caller.admin() ? null : caller.userId();
        Specification<Order> specification = Specification.allOf(
                OrderSpecifications.hasUserId(userId),
                OrderSpecifications.createdFrom(from),
                OrderSpecifications.createdThrough(to),
                OrderSpecifications.hasStatuses(statuses));
        return findOrders(specification, pageable);
    }

    public Page<OrderResponse> getByUserId(Long userId, Pageable pageable, OrderPrincipal caller) {
        requireAccess(userId, caller);
        return findOrders(OrderSpecifications.hasUserId(userId), pageable);
    }

    @Transactional
    public OrderResponse update(Long id, UpdateOrderRequest request, OrderPrincipal caller) {
        OrderStatus status = request.status();
        List<OrderItemRequest> items = request.items();
        if (status == null && items == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide status or items");
        }

        Order order = findForUpdate(id, caller);
        validateUpdate(order.getStatus(), status, items != null, caller);
        if (items != null) {
            setItems(order, items);
        }
        if (status != null) {
            order.setStatus(status);
        }
        auditingHandler.markModified(order);
        orderRepository.flush();
        return orderMapper.toResponse(order);
    }

    @Transactional
    public void delete(Long id, OrderPrincipal caller) {
        if (!caller.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can delete an order");
        }
        findForUpdate(id, caller).markDeleted();
    }

    private Order findForUpdate(Long id, OrderPrincipal caller) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        requireAccess(order.getUserId(), caller);
        return order;
    }

    private Page<OrderResponse> findOrders(Specification<Order> specification, Pageable pageable) {
        for (Sort.Order sort : pageable.getSort()) {
            if (!SORT_FIELDS.contains(sort.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort field: " + sort.getProperty());
            }
        }
        return orderRepository.findAll(specification, pageable).map(orderMapper::toResponse);
    }

    private void validateUpdate(OrderStatus current, OrderStatus target, boolean changesItems, OrderPrincipal caller) {
        if (target != null && target != OrderStatus.CANCELLED && !caller.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can advance an order");
        }
        if (changesItems && current != OrderStatus.NEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Items can only be edited on a new order");
        }
        if (target == null) {
            return;
        }
        boolean allowed = switch (current) {
            case NEW -> target == OrderStatus.PAID || target == OrderStatus.CANCELLED;
            case PAID -> target == OrderStatus.SHIPPED;
            case SHIPPED -> target == OrderStatus.DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid order status transition");
        }
    }

    private void setItems(Order order, List<OrderItemRequest> requests) {
        List<Long> itemIds = requests.stream().map(OrderItemRequest::itemId).distinct().toList();
        Map<Long, Item> itemsById = new HashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            itemsById.put(item.getId(), item);
        }

        List<OrderItem> items = requests.stream().map(request -> {
            Item item = itemsById.get(request.itemId());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item " + request.itemId() + " not found");
            }
            return orderMapper.toEntity(request, item);
        }).toList();

        BigDecimal total = items.stream()
                .map(item -> item.getItem().getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(TOTAL_PRICE_LIMIT) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order total is too large");
        }
        order.updateItems(items);
        order.setTotalPrice(total);
    }

    private void requireAccess(Long userId, OrderPrincipal caller) {
        if (!caller.admin() && !caller.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found");
    }
}
