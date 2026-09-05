package io.github.erdsgfc.postgresql.entity;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.WritePolicy;

import java.time.LocalDateTime;

public interface BaseEntity {

    @Column(name = "input_time", write = WritePolicy.INSERT_ONLY)
    default LocalDateTime inputTime() {
        return LocalDateTime.now();
    }

    @Column(name = "update_time", write = WritePolicy.BOTH)
    default LocalDateTime updateTime() {
        return LocalDateTime.now();
    }

    @Column(name = "input_admin_id", write = WritePolicy.INSERT_ONLY)
    default long inputAdminId() {
        return 1;
    }

    @Column(name = "update_admin_id", write = WritePolicy.INSERT_ONLY)
    default long updateAdminId() {
        return 1;
    }

    @Column(name = "input_admin_name", write = WritePolicy.INSERT_ONLY)
    default String inputAdminName() {
        return "admin";
    }

    @Column(name = "update_admin_name", write = WritePolicy.INSERT_ONLY)
    default String updateAdminName() {
        return "admin";
    }
}
