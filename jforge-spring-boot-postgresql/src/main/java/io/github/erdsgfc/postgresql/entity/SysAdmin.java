package io.github.erdsgfc.postgresql.entity;

import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;
import org.jspecify.annotations.NonNull;

@Table(name = "sys_admin")
public interface SysAdmin {
    @Id
    long  adminId();
    @NonNull
    String  adminName();
}
