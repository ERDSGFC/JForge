package com.qin.orm.core;

import java.util.List;
import java.util.StringJoiner;

/** Builds parameterized CRUD SQL from {@link EntityMetadata}. */
public final class SqlGenerator {

    private SqlGenerator() {
    }

    /** INSERT INTO t (c1,c2,...) VALUES (?,?,...) — generated id excluded. */
    public static String insertSql(EntityMetadata meta) {
        String table = meta.tableName();
        String columns = join(meta.insertColumns());
        String placeholders = placeholders(meta.insertColumns().size());
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    /** UPDATE t SET c1=?,c2=?,... WHERE id=? — id excluded from SET. */
    public static String updateSql(EntityMetadata meta) {
        String table = meta.tableName();
        StringJoiner sets = new StringJoiner(",");
        for (String column : meta.updateColumns()) {
            sets.add(column + "=?");
        }
        return "UPDATE " + table + " SET " + sets + " WHERE " + meta.idColumnName() + "=?";
    }

    /** DELETE FROM t WHERE id=?. */
    public static String deleteSql(EntityMetadata meta) {
        return "DELETE FROM " + meta.tableName() + " WHERE " + meta.idColumnName() + "=?";
    }

    /** SELECT c1,c2,... FROM t WHERE id=?. */
    public static String selectByIdSql(EntityMetadata meta) {
        return "SELECT " + selectColumns(meta) + " FROM " + meta.tableName()
                + " WHERE " + meta.idColumnName() + "=?";
    }

    /** SELECT c1,c2,... FROM t. */
    public static String selectAllSql(EntityMetadata meta) {
        return "SELECT " + selectColumns(meta) + " FROM " + meta.tableName();
    }

    /** Comma-joined selectable columns. */
    public static String selectColumns(EntityMetadata meta) {
        return join(meta.columnNames());
    }

    private static String join(List<String> items) {
        return String.join(",", items);
    }

    private static String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(",");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }
}
