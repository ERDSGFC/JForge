package io.github.erdsgfc.jforge.annotation;

/**
 * {@link Join} 使用的 SQL 连接类型。
 */
public enum JoinType {

    /** 只保留两侧都满足连接条件的行。 */
    INNER("INNER JOIN"),

    /** 保留左侧全部行，右侧无匹配时以 {@code NULL} 补齐。 */
    LEFT("LEFT JOIN"),

    /** 保留右侧全部行，左侧无匹配时以 {@code NULL} 补齐。 */
    RIGHT("RIGHT JOIN"),

    /** 保留两侧全部行，无匹配的一侧以 {@code NULL} 补齐。 */
    FULL("FULL JOIN"),

    /** 生成两张表的笛卡尔积，不允许声明 {@link Join.On} 条件。 */
    CROSS("CROSS JOIN");

    private final String sql;

    JoinType(String sql) {
        this.sql = sql;
    }

    /**
     * 返回该连接类型的 SQL 关键字。
     *
     * @return SQL 关键字
     */
    public String sql() {
        return sql;
    }
}
