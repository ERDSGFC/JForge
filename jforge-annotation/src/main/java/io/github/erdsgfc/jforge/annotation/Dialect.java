package io.github.erdsgfc.jforge.annotation;

/** SQL 方言——决定注解处理器生成的 SQL 风格。 */
public enum Dialect {

    /** PostgreSQL / H2 PostgreSQL 兼容模式。 */
    POSTGRESQL,

    /** MySQL / MariaDB。 */
    MYSQL;
}