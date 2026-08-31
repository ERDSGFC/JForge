package io.github.erdsgfc.jforge.processor.dialect;

import io.github.erdsgfc.jforge.annotation.DialectSupport;

/** PostgreSQL（真 PG）的内建方言实现（编译期使用，不进框架 jar）——H2 请用 {@link H2Dialect}。 */
public final class PostgreSqlDialect implements DialectSupport {

    @Override
    public boolean supportsReturningKeys() {
        // INSERT ... RETURNING 是真 PG 官方推荐的生成键路径(单语句拿 id,优于
        // getGeneratedKeys);H2 已有独立方言(H2Dialect,不支持该语法),POSTGRESQL
        // 枚举值现仅代表真 PG。
        return true;
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
