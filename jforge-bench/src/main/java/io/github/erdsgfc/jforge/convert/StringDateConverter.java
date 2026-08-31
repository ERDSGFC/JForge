package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.JForgeConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 自定义类型转换器：{@code LocalDate} ↔ VARCHAR 字符串（{@code "yyyy-MM-dd"}）——
 * 把 {@code LocalDate} 列存为可读的文本而非数据库日期类型。转换器须有公开无参
 * 构造器，且 {@code toDatabase}/{@code toEntity} 接受并透传 {@code null}。
 */
public final class StringDateConverter implements JForgeConverter<LocalDate, String> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String toDatabase(LocalDate attribute) {
        return attribute == null ? null : FORMAT.format(attribute);
    }

    @Override
    public LocalDate toEntity(String dbData) {
        return dbData == null ? null : LocalDate.parse(dbData, FORMAT);
    }
}
