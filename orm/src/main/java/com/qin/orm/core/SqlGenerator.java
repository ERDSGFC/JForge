package com.qin.orm.core;

import java.util.List;
import java.util.StringJoiner;

/**
 * Builds parameterized CRUD SQL from plain mapping data. Called only once per entity,
 * at {@link EntityMetadata} parse time; the results are cached, never rebuilt per operation.
 */
public final class SqlGenerator {

    private SqlGenerator() {
    }

    /** INSERT INTO t (c1,c2,...) VALUES (?,?,...) — generated id excluded. */
    public static String insertSql(String tableName, List<String> columns) {
        return "INSERT INTO " + tableName + " (" + join(columns) + ") VALUES (" + placeholders(columns.size()) + ")";
    }

    /** UPDATE t SET c1=?,c2=?,... WHERE id=? — id excluded from SET. */
    public static String updateSql(String tableName, List<String> columns, String idColumn) {
        StringJoiner sets = new StringJoiner(",");
        for (String column : columns) {
            sets.add(column + "=?");
        }
        return "UPDATE " + tableName + " SET " + sets + " WHERE " + idColumn + "=?";
    }

    /** DELETE FROM t WHERE id=?. */
    public static String deleteSql(String tableName, String idColumn) {
        return "DELETE FROM " + tableName + " WHERE " + idColumn + "=?";
    }

    /** SELECT c1,c2,... FROM t WHERE id=?. */
    public static String selectByIdSql(String tableName, List<String> columns, String idColumn) {
        return "SELECT " + join(columns) + " FROM " + tableName + " WHERE " + idColumn + "=?";
    }

    /** SELECT c1,c2,... FROM t. */
    public static String selectAllSql(String tableName, List<String> columns) {
        return "SELECT " + join(columns) + " FROM " + tableName;
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
