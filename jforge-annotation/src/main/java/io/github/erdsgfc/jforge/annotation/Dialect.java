package io.github.erdsgfc.jforge.annotation;

/** SQL 方言——决定注解处理器生成的 SQL 风格。 */
public enum Dialect {

    /** PostgreSQL（真 PG）——生成键走 {@code INSERT ... RETURNING} 优化路径。 */
    POSTGRESQL,

    /** MySQL / MariaDB。 */
    MYSQL,

    /** SQLite（3.35+，生成键支持 RETURNING）。 */
    SQLITE,

    /** H2 数据库（含 {@code MODE=PostgreSQL} 兼容模式）——2.3 实测不支持
     *  {@code INSERT ... RETURNING}，生成键走 JDBC 标准 {@code getGeneratedKeys}。 */
    H2;
}