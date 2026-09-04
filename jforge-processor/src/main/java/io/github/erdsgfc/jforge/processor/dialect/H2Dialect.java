package io.github.erdsgfc.jforge.processor.dialect;

import io.github.erdsgfc.jforge.annotation.DialectSupport;

/**
 * H2 数据库（含 {@code MODE=PostgreSQL} 兼容模式）的内建方言实现（编译期使用，不进框架 jar）。
 *
 * <p>与 {@link PostgreSqlDialect} 的唯一差异：{@link #supportsReturningKeys()} 返回
 * {@code false}——H2 2.3 实测不支持 {@code INSERT ... RETURNING} 语法，生成键必须走
 * JDBC 标准 {@code getGeneratedKeys}。其余能力（引用符/自增 DDL/分页/UPSERT/布尔）在
 * {@code MODE=PostgreSQL} 下与真 PG 等价。</p>
 */
public final class H2Dialect implements DialectSupport {

    @Override
    public boolean supportsReturningKeys() {
        return false; // H2 2.3 不支持 INSERT ... RETURNING，走 JDBC 标准 getGeneratedKeys
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

    @Override
    public boolean supportsDollarQuotedStrings() {
        return true;
    }

    @Override
    public boolean supportsDoubleColonCast() {
        return true;
    }
}
