package com.qin.orm.core;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads one column from a {@link ResultSet} using the type-exact getter
 * (e.g. {@code getLong} instead of {@code getObject}), determined once at metadata parse time.
 * Returns {@code null} for SQL NULL (also on primitive-typed fields).
 */
@FunctionalInterface
public interface RowReader {

    Object read(ResultSet rs, String column) throws SQLException;
}
