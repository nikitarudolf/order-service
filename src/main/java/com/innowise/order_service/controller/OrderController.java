package com.innowise.order_service.controller;

import com.innowise.order_service.dto.CreateOrderRequest;
import com.innowise.order_service.dto.OrderResponse;
import com.innowise.order_service.dto.UpdateOrderRequest;
import com.innowise.order_service.entity.OrderStatus;
import com.innowise.order_service.security.OrderPrincipal;
import com.innowise.order_service.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                @AuthenticationPrincipal OrderPrincipal caller) {
        OrderResponse response = orderService.create(request, caller);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.order().id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getById(@PathVariable @Positive Long id,
                                                 @AuthenticationPrincipal OrderPrincipal caller) {
        return ResponseEntity.ok(orderService.getById(id, caller));
    }

    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Set<OrderStatus> statuses,
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @AuthenticationPrincipal OrderPrincipal caller) {
        return ResponseEntity.ok(orderService.getAll(createdFrom, createdTo, statuses, pageable, caller));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<OrderResponse>> getByUserId(@PathVariable @Positive Long userId,
                                                         @PageableDefault(size = 20, sort = "id") Pageable pageable,
                                                         @AuthenticationPrincipal OrderPrincipal caller) {
        return ResponseEntity.ok(orderService.getByUserId(userId, pageable, caller));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<OrderResponse> update(@PathVariable @Positive Long id,
                                                @Valid @RequestBody UpdateOrderRequest request,
                                                @AuthenticationPrincipal OrderPrincipal caller) {
        return ResponseEntity.ok(orderService.update(id, request, caller));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long id,
                                       @AuthenticationPrincipal OrderPrincipal caller) {
        orderService.delete(id, caller);
        return ResponseEntity.noContent().build();
    }
}
