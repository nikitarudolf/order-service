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
import com.innowise.order_service.entity.UserSnapshot;
import com.innowise.order_service.mapper.OrderMapper;
import com.innowise.order_service.repository.ItemRepository;
import com.innowise.order_service.repository.OrderRepository;
import com.innowise.order_service.security.OrderPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final OrderPrincipal CALLER = new OrderPrincipal(7L, false, "test-token");
    private static final OrderPrincipal ADMIN = new OrderPrincipal(9L, true, "admin-token");
    private static final UserInfo USER = new UserInfo(7L, "Alex", "Smith", null, "alex@example.com", true);
    private static final List<OrderItemRequest> ITEMS = List.of(new OrderItemRequest(1L, 2));

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private AuditingHandler auditingHandler;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, itemRepository, Mappers.getMapper(OrderMapper.class),
                userServiceClient, auditingHandler);
    }

    @Test
    void createUsesCatalogPriceAndResolvedUser() {
        when(userServiceClient.getByEmail(USER.email(), CALLER.token())).thenReturn(USER);
        when(itemRepository.findAllById(List.of(1L))).thenReturn(List.of(item()));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(new CreateOrderRequest(USER.email(), ITEMS), CALLER);

        assertThat(response.user()).isEqualTo(USER);
        assertThat(response.order().userId()).isEqualTo(USER.id());
        assertThat(response.order().totalPrice()).isEqualByComparingTo("25.00");
        assertThat(response.order().status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.order().items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
    }

    @Test
    void createRejectsUnknownItem() {
        when(userServiceClient.getByEmail(USER.email(), CALLER.token())).thenReturn(USER);
        when(itemRepository.findAllById(List.of(1L))).thenReturn(List.of());
        CreateOrderRequest request = new CreateOrderRequest(USER.email(), ITEMS);

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.create(request, CALLER))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void getByIdReturnsSavedSnapshot() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order()));

        assertThat(service.getById(1L, CALLER).user()).isEqualTo(USER);
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void getByIdRejectsAnotherUsersOrder() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order()));
        OrderPrincipal stranger = new OrderPrincipal(8L, false, "other-token");

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.getById(1L, stranger))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void getAllReturnsEachOrdersSnapshotForTheSameBuyer() {
        UserInfo olderUser = new UserInfo(USER.id(), "Alex", "Jones", null, "old@example.com", false);
        PageRequest pageable = PageRequest.of(0, 5);
        when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order(olderUser), order()), pageable, 2));

        assertThat(service.getAll(null, null, Set.of(OrderStatus.NEW), pageable, CALLER).getContent())
                .extracting(OrderResponse::user)
                .containsExactly(olderUser, USER);
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void getAllRejectsInvertedDateRange() {
        LocalDate from = LocalDate.of(2026, 2, 2);
        LocalDate to = from.minusDays(1);
        PageRequest pageable = PageRequest.of(0, 5);

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.getAll(from, to, null, pageable, CALLER))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void getByUserIdReturnsPageWithSnapshot() {
        PageRequest pageable = PageRequest.of(0, 5);
        when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order()), pageable, 1));

        assertThat(service.getByUserId(USER.id(), pageable, CALLER).getContent()).singleElement()
                .satisfies(response -> assertThat(response.user()).isEqualTo(USER));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void updateStatusOnlyKeepsItemsAndTotal() {
        Order order = order();
        OrderItem line = new OrderItem();
        line.setId(10L);
        line.setItem(item());
        line.setQuantity(2);
        order.updateItems(List.of(line));
        order.setTotalPrice(new BigDecimal("25.00"));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        var response = service.update(1L, new UpdateOrderRequest(OrderStatus.CANCELLED, null), CALLER);

        assertThat(response.order().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.order().totalPrice()).isEqualByComparingTo("25.00");
        assertThat(response.order().items()).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(10L));
        assertThat(response.user()).isEqualTo(USER);
        verifyNoInteractions(itemRepository, userServiceClient);
        verify(auditingHandler).markModified(order);
        verify(orderRepository).flush();
    }

    @Test
    void updateItemsKeepsExistingLineAndLeavesStatusUnchanged() {
        Order order = order();
        OrderItem line = new OrderItem();
        line.setId(10L);
        line.setItem(item());
        line.setQuantity(1);
        order.updateItems(List.of(line));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(itemRepository.findAllById(List.of(1L))).thenReturn(List.of(item()));

        var response = service.update(1L, new UpdateOrderRequest(null, ITEMS), CALLER);

        assertThat(response.order().status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.order().totalPrice()).isEqualByComparingTo("25.00");
        assertThat(response.order().items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(10L);
            assertThat(item.quantity()).isEqualTo(2);
        });
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void rejectsItemsWhenStatusTransitionIsForbiddenForOwner() {
        Order order = order();
        order.setTotalPrice(new BigDecimal("12.50"));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest(OrderStatus.PAID, ITEMS);

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.update(1L, request, CALLER))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(order.getTotalPrice()).isEqualByComparingTo("12.50");
        assertThat(order.getItems()).isEmpty();
        verifyNoInteractions(itemRepository, userServiceClient, auditingHandler);
    }

    @Test
    void deleteMarksOrderDeletedWithoutCallingUserService() {
        Order order = order();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        service.delete(1L, ADMIN);

        assertThat(order.isDeleted()).isTrue();
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void deleteRejectsOwnerBeforeLookingUpOrder() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.delete(1L, CALLER))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(orderRepository, userServiceClient);
    }

    @Test
    void deleteRejectsMissingOrder() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.delete(1L, ADMIN))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Item item() {
        Item item = new Item();
        ReflectionTestUtils.setField(item, "id", 1L);
        ReflectionTestUtils.setField(item, "price", new BigDecimal("12.50"));
        return item;
    }

    private Order order() {
        return order(USER);
    }

    private Order order(UserInfo user) {
        return new Order(user.id(), new UserSnapshot(user.name(), user.surname(), user.birthDate(),
                user.email(), user.active()));
    }
}
