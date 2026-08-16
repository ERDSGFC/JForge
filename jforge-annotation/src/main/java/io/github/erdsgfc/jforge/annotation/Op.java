package io.github.erdsgfc.jforge.annotation;

/**
 * {@link Where} 指定的查询条件操作符。
 */
public enum Op {

    /** {@code col = ?} */
    EQ("="),

    /** {@code col <> ?} */
    NE("<>"),

    /** {@code col > ?} */
    GT(">"),

    /** {@code col < ?} */
    LT("<"),

    /** {@code col >= ?} */
    GE(">="),

    /** {@code col <= ?} */
    LE("<="),

    /** {@code col LIKE ?} */
    LIKE("LIKE"),

    /** {@code col NOT LIKE ?} */
    NOT_LIKE("NOT LIKE");

    private final String sql;

    Op(String sql) {
        this.sql = sql;
    }

    /** 操作符的 SQL 片段（用于 {@code col op ?} 的拼接）。 */
    public String sql() {
        return sql;
    }
}
