package io.github.erdsgfc.jforge.pgsql;

/** 用户状态——PG 原生枚举类型（{@code CREATE TYPE ... AS ENUM}）的映射验证。 */
public enum PgUserStatus {
    ACTIVE,
    INACTIVE
}
