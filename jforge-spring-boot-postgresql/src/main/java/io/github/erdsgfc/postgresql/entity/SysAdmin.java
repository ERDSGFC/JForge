package io.github.erdsgfc.postgresql.entity;

import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

@Table(name = "sys_admin")
public interface SysAdmin extends BaseEntity {
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
}
