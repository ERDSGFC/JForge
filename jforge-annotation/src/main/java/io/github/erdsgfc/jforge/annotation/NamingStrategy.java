package io.github.erdsgfc.jforge.annotation;

/**
 * 标识符（列名/表名）的命名策略。两处独立使用：
 * <ul>
 *   <li>{@link JForgeConfig#naming()}——没有 {@code @Column} 注解时，属性方法名
 *       如何映射为数据库列名（默认 {@code NONE}）；</li>
 *   <li>{@link JForgeConfig#tableNaming()}——没有 {@code @Table} 注解（或 {@code name}
 *       为空）时，实体接口名如何映射为表名（默认 {@code CAMEL_TO_SNAKE}，snake_case
 *       表名是数据库惯例）。</li>
 * </ul>
 *
 * <p>两种策略产出的名字都会被方言引用符包裹、按精确名匹配数据库（编译期由
 * {@code SqlCodegen.quoteIdentifier} 实施）。因此 {@link #NONE} 产生的驼峰/大写名
 * 需要 DDL 同样加引号，而 {@link #CAMEL_TO_SNAKE} 产出全小写名，与无引号 DDL 的
 * 折叠结果天然一致、无需特殊处理。</p>
 */
public enum NamingStrategy {

    /**
     * 名称原样保留（例如方法名 {@code userName()} → 列 {@code userName}；
     * 实体名 {@code UserEntity} → 表 {@code UserEntity}），大小写与拼写均不转换。
     *
     * <p><strong>与 DDL 的契约</strong>：生成的 SQL 会按方言引用符包裹这些名字
     * （编译期由 {@code SqlCodegen.quoteIdentifier} 实施），
     * 数据库端做精确匹配、<em>不折叠大小写</em>。因此 DDL 必须写出同样的精确名——
     * {@code userName()} 需要 {@code CREATE TABLE t ("userName" VARCHAR(100))}；
     * 若 DDL 未引用（{@code userName} 会被 PG 折叠为 {@code username}），
     * 则本策略下查询不到该列。</p>
     */
    NONE,

    /** 将小驼峰转换为蛇形命名（例如 {@code userName()} → 列 {@code user_name}；
     *  {@code UserEntity} → 表 {@code user_entity}）。 */
    CAMEL_TO_SNAKE;
}