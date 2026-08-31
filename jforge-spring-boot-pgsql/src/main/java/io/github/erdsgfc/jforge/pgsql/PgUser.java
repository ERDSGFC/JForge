package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 真 PostgreSQL 集成测试实体：验证生成 SQL（引用符包裹、{@code INSERT ... RETURNING}
 * 生成键回写）在真实 PG 上的行为。
 *
 * <p>{@code order} 是 PostgreSQL 保留字列——生成的 SQL 必须按方言引用符包裹
 * （{@code "order"}）才能解析，是引用符能力在真库上的直接验证。</p>
 */
@Table(name = "pg_users")
public interface PgUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    PgUser id(Long id);

    /** 显示名，经命名策略（CAMEL_TO_SNAKE）映射到 {@code user_name} 列。 */
    String userName();

    PgUser userName(String userName);

    /** 年龄（岁）。 */
    Integer age();

    PgUser age(Integer age);

    /** 排序序号——保留字列（{@code order}），显式列名。 */
    @Column(name = "order")
    Integer order();

    PgUser order(Integer order);

    /** 城市（嵌套条件组 {@link PgAddressCriteria} 的映射字段）。 */
    String city();

    PgUser city(String city);

    /** 街道（嵌套条件组 {@link PgAddressCriteria} 的映射字段）。 */
    String street();

    PgUser street(String street);

    /** 创建时间——default getter 作为属性默认值来源（save 自动取值绑定，与 RETURNING 同语句）。 */
    @Column(name = "created_at")
    default java.time.LocalDateTime createdAt() {
        return java.time.LocalDateTime.now();
    }

    PgUser createdAt(java.time.LocalDateTime createdAt);

    // ---- 各种数据库字段类型（真 PG 上的 JDBC 绑定/读取矩阵验证）----

    /** 布尔 → BOOLEAN。 */
    Boolean active();

    PgUser active(Boolean active);

    /** 高精度小数 → NUMERIC。 */
    java.math.BigDecimal balance();

    PgUser balance(java.math.BigDecimal balance);

    /** 日期 → DATE（getObject 路径）。 */
    java.time.LocalDate birthDate();

    PgUser birthDate(java.time.LocalDate birthDate);

    /** 双精度 → DOUBLE PRECISION。 */
    Double height();

    PgUser height(Double height);

    /** 单精度 → REAL。 */
    Float weight();

    PgUser weight(Float weight);

    /** 短整型 → SMALLINT。 */
    Short level();

    PgUser level(Short level);

    /** 字节数组 → BYTEA。 */
    byte[] avatar();

    PgUser avatar(byte[] avatar);

    /** 枚举 → PG 原生枚举类型（CREATE TYPE ... AS ENUM）。 */
    PgUserStatus status();

    PgUser status(PgUserStatus status);
}
