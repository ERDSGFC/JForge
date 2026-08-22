package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.DialectSupport;

/** MySQL / MariaDB 的内建方言实现（编译期使用，不进框架 jar）。 */
final class MySqlDialect implements DialectSupport {

    @Override
    public boolean supportsReturningKeys() {
        return false; // 走 JDBC 标准 getGeneratedKeys
    }

    @Override
    public String serialType() {
        return "BIGINT AUTO_INCREMENT";
    }

    @Override
    public String quote() {
        return "`";
    }

    @Override
    public String limitClause() {
        return "LIMIT ? OFFSET ?";
    }

    @Override
    public String upsertClause() {
        return "ON DUPLICATE KEY";
    }

    @Override
    public String booleanLiteral() {
        return "1";
    }
}
