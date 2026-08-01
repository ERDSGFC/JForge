package com.qin.orm.core;

import com.qin.orm.OrmException;

import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Default {@link RowMapper}: instantiates the entity and assigns each column via the metadata's
 * {@code MethodHandle} setters, reading columns with the pre-resolved type-exact {@link RowReader}s.
 */
public final class DefaultRowMapper<T> implements RowMapper<T> {

    private final EntityMetadata meta;
    private final List<RowReader> readers;

    public DefaultRowMapper(EntityMetadata meta) {
        this.meta = meta;
        this.readers = meta.rowReaders();
    }

    @Override
    public T map(ResultSet rs) throws SQLException {
        T entity = newInstance();
        for (int i = 0; i < readers.size(); i++) {
            Object value = readers.get(i).read(rs, meta.columnNames().get(i));
            meta.setValue(entity, i, value);
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
}
