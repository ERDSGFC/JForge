package com.qin.orm.core;

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

    /**
     * Maps the current row to a new entity: instance via the metadata's factory
     * (generated direct constructor or MethodHandle), columns via the pre-resolved
     * type-exact readers (1-based column index — no findColumn lookup).
     */
    @Override
    @SuppressWarnings("unchecked")
    public T map(ResultSet rs) throws SQLException {
        T entity = (T) meta.newInstance();
        // Column index = row position + 1: SELECT column order always matches columnNames()
        // (see SqlGenerator / generated selectByIdSql/selectAllSql), so no findColumn lookup.
        for (int i = 0; i < readers.size(); i++) {
            Object value = readers.get(i).read(rs, i + 1);
            meta.setValue(entity, i, value);
        }
        return entity;
    }
}
