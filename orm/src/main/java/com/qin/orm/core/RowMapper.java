package com.qin.orm.core;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps a single ResultSet row to an entity instance. */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet rs) throws SQLException;
}
