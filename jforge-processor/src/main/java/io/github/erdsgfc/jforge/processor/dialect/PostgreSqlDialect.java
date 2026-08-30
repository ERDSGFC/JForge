package io.github.erdsgfc.jforge.processor.dialect;

import io.github.erdsgfc.jforge.annotation.DialectSupport;

/** PostgreSQL / H2（MODE=PostgreSQL）的内建方言实现（编译期使用，不进框架 jar）。 */
public final class PostgreSqlDialect implements DialectSupport {

    @Override
    public boolean supportsReturningKeys() {
        // 暂不启用:INSERT ... RETURNING 是真 PG 的优化路径,但 H2 2.3(测试库,同样
        // 走 POSTGRESQL 方言)不支持该语法——方言无法区分两者,统一走 JDBC 标准
        // getGeneratedKeys。待引入独立 H2 方言或运行时驱动探测后再启用。
        return false;
    }

    @Override
    public String serialType() {
        return "BIGSERIAL";
    }

    @Override
    public String quote() {
        return "\"";
    }

    @Override
    public String limitClause() {
        return "LIMIT ? OFFSET ?";
    }

    @Override
    public String upsertClause() {
        return "ON CONFLICT";
    }

    @Override
    public String booleanLiteral() {
        return "TRUE";
    }
}
