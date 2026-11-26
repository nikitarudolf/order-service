package com.innowise.order_service.dto;

import java.time.LocalDate;

public record UserInfo(Long id, String name, String surname, LocalDate birthDate, String email, boolean active) {
}
