package io.github.erdsgfc.postgresql.entity;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;
import io.github.erdsgfc.jforge.annotation.WritePolicy;

import java.time.LocalDateTime;

@Table(name = "sys_admin")
public interface SysAdmin {
    @Id
    long  adminId();
    SysAdmin adminId(long adminId);

    String  adminName();
    SysAdmin adminName( String adminName);

    String adminMobile();
    SysAdmin adminMobile(String adminMobile);

    String adminPassword();
    SysAdmin adminPassword(String adminPassword);

    int adminStatus();
    SysAdmin adminStatus(int adminStatus);

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
