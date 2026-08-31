package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.annotation.JForgeConverter;

import java.util.UUID;

/**
 * 自定义类型转换器：{@code UUID} ↔ VARCHAR 字符串——把 UUID 存为可读文本
 * （{@code UUID.toString()}/{@code UUID.fromString}），验证 {@code @Convert}
 * 在真 PostgreSQL 上的绑定/读取。
 */
public final class UuidStringConverter implements JForgeConverter<UUID, String> {

    @Override
    public String toDatabase(UUID attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public UUID toEntity(String dbData) {
        return dbData == null ? null : UUID.fromString(dbData);
    }
}
