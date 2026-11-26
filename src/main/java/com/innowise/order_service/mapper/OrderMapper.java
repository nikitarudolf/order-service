package com.innowise.order_service.mapper;

import com.innowise.order_service.dto.OrderDetails;
import com.innowise.order_service.dto.OrderItemRequest;
import com.innowise.order_service.dto.OrderItemResponse;
import com.innowise.order_service.dto.OrderResponse;
import com.innowise.order_service.dto.UserInfo;
import com.innowise.order_service.entity.Item;
import com.innowise.order_service.entity.Order;
import com.innowise.order_service.entity.OrderItem;
import com.innowise.order_service.entity.UserSnapshot;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "item", source = "item")
    @Mapping(target = "quantity", source = "request.quantity")
    OrderItem toEntity(OrderItemRequest request, Item item);

    OrderDetails toDetails(Order order);

    @Mapping(target = "itemId", source = "item.id")
    OrderItemResponse toResponse(OrderItem orderItem);

    default UserSnapshot toSnapshot(UserInfo user) {
        return new UserSnapshot(user.name(), user.surname(), user.birthDate(), user.email(), user.active());
    }

    default OrderResponse toResponse(Order order) {
        UserSnapshot snapshot = order.getUserSnapshot();
        UserInfo user = snapshot == null ? null : new UserInfo(order.getUserId(), snapshot.getName(),
                snapshot.getSurname(), snapshot.getBirthDate(), snapshot.getEmail(), snapshot.getActive());
        return new OrderResponse(toDetails(order), user);
    }
}
