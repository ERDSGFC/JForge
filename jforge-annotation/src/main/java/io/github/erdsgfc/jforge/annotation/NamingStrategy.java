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
 */
public enum NamingStrategy {

    /** 名称原样保留（例如方法名 {@code userName()} → 列 {@code userName}；
     *  实体名 {@code UserEntity} → 表 {@code UserEntity}）。 */
    NONE,

    /** 将小驼峰转换为蛇形命名（例如 {@code userName()} → 列 {@code user_name}；
     *  {@code UserEntity} → 表 {@code user_entity}）。 */
    CAMEL_TO_SNAKE;
}