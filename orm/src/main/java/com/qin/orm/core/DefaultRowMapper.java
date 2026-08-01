package com.qin.orm.core;

import com.qin.orm.OrmException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Default {@link RowMapper}: instantiates the entity and assigns each column via
 * the metadata's {@code MethodHandle} setters, with JDBC-level type conversion.
 */
public final class DefaultRowMapper<T> implements RowMapper<T> {

    private final EntityMetadata meta;
    private final Map<String, Field> columnToField;

    public DefaultRowMapper(EntityMetadata meta) {
        this.meta = meta;
        this.columnToField = new HashMap<>(meta.columnNames().size());
        for (int i = 0; i < meta.fields().size(); i++) {
            columnToField.put(meta.columnNames().get(i), meta.fields().get(i));
        }
    }

    @Override
    public T map(ResultSet rs) throws SQLException {
        T entity = newInstance();
        for (Map.Entry<String, Field> entry : columnToField.entrySet()) {
            Object value = readColumn(rs, entry.getKey(), entry.getValue().getType());
            meta.setFieldValue(entity, entry.getValue(), value);
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private T newInstance() {
        try {
            return (T) meta.entityClass().getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException e) {
            throw new OrmException("Entity needs a public no-arg constructor: " + meta.entityClass(), e);
        }
    }

    private Object readColumn(ResultSet rs, String column, Class<?> fieldType) throws SQLException {
        Class<?> wrapped = wrap(fieldType);
        try {
            return rs.getObject(column, wrapped);
        } catch (SQLException e) {
            return rs.getObject(column);
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
