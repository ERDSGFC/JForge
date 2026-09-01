package io.github.erdsgfc.jforge.convert;

import io.github.erdsgfc.jforge.annotation.JForgeConverter;

import java.sql.JDBCType;
import java.sql.SQLType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 覆盖 {@link JForgeConverter#sqlType()} 的转换器：{@code LocalDate} ↔ VARCHAR 文本
 * （与 {@link StringDateConverter} 同构），但显式声明绑定 SQL 类型为
 * {@link JDBCType#VARCHAR}（而非默认的 {@code JDBCType.OTHER}）——验证用户自定义
 * 绑定 SQL 类型的生成路径（{@code ps.setObject(i, v, CONV.sqlType())}）。
 */
public final class VarcharDateConverter implements JForgeConverter<LocalDate> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Object toDatabase(LocalDate attribute) {
        return attribute == null ? null : FORMAT.format(attribute);
    }

    @Override
    public LocalDate toEntity(Object dbData) {
        return dbData == null ? null : LocalDate.parse((String) dbData, FORMAT);
    }

    @Override
    public SQLType sqlType() {
        return JDBCType.VARCHAR;
    }
}