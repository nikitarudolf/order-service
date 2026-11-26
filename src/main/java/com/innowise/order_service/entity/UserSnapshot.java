package com.innowise.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserSnapshot {

    @Column(name = "user_snapshot_name", updatable = false)
    private String name;

    @Column(name = "user_snapshot_surname", updatable = false)
    private String surname;

    @Column(name = "user_snapshot_birth_date", updatable = false)
    private LocalDate birthDate;

    @Column(name = "user_snapshot_email", updatable = false)
    private String email;

    @Column(name = "user_snapshot_active", updatable = false)
    private Boolean active;
}
