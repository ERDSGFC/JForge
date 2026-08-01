package com.qin.orm.core;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds one parameter value to a {@link PreparedStatement} using the type-exact setter
 * (e.g. {@code setLong} instead of {@code setObject}), determined once at metadata parse time.
 */
@FunctionalInterface
public interface ParamBinder {

    void bind(PreparedStatement ps, int index, Object value) throws SQLException;
}
