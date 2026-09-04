package io.github.erdsgfc.jforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为声明式 {@link Select} 查询增加一个经过编译期校验的表连接。
 *
 * <p>连接目标和两侧字段都使用实体类型/字段名表达，处理器负责解析表列映射及数据库方言引用符。
 * {@link #from()} 默认是仓库的宿主实体；连接多个表时，也可以指向此前已经连接的实体。一个查询中
 * 每种实体最多出现一次，因此当前设计有意不支持自连接和同表多别名。</p>
 *
 * <pre>{@code
 * @Select
 * @Join(entity = Department.class,
 *       on = @Join.On(local = "departmentId", target = "id"),
 *       type = JoinType.LEFT)
 * List<User> findByDepartmentName(
 *       @Condition(value = "name", entity = Department.class) String departmentName);
 *
 * @Select
 * @Join(entity = Department.class,
 *       on = @Join.On(local = "departmentId", target = "id"))
 * @Join(entity = Company.class, from = Department.class,
 *       on = @Join.On(local = "companyId", target = "id"))
 * List<User> findByCompanyId(
 *       @Condition(value = "id", entity = Company.class) long companyId);
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Joins.class)
public @interface Join {

    /**
     * 要连接的目标实体。
     *
     * @return 目标实体类型
     */
    Class<?> entity();

    /**
     * ON 条件左侧所属实体。默认的 {@code void.class} 表示当前仓库的宿主实体。
     * 指定的实体必须是宿主实体或当前注解之前已经连接的实体。
     *
     * @return ON 条件左侧实体，或 {@code void.class}
     */
    Class<?> from() default void.class;

    /**
     * 连接类型。
     *
     * @return 连接类型
     */
    JoinType type() default JoinType.INNER;

    /**
     * ON 字段对。普通连接至少需要一个字段对；{@link JoinType#CROSS} 不允许字段对。
     * 多个字段对之间使用 {@code AND} 连接。
     *
     * @return ON 字段对
     */
    On[] on() default {};

    /**
     * 描述一个 ON 等值条件：{@link #local()} 属于 {@link Join#from()}，
     * {@link #target()} 属于 {@link Join#entity()}。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface On {

        /**
         * 左侧实体字段名，即实体 getter 名。
         *
         * @return 左侧字段名
         */
        String local();

        /**
         * 目标实体字段名，即实体 getter 名。
         *
         * @return 目标字段名
         */
        String target();
    }
}
