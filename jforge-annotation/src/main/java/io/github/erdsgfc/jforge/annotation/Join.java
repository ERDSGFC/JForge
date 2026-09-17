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
 * 一个查询中每种实体最多出现一次，因此当前设计有意不支持自连接和同表多别名。</p>
 *
 * <p><b>链式连接无需写 {@link #from()}</b>：未指定时处理器从 {@link On#local()} 字段
 * <b>推断</b>——该字段所属的可用实体（宿主或此前已连接的实体）即为左侧。若同一字段名在
 * 多个已连接实体中都存在（歧义，如两侧都有 {@code id}），编译报错要求显式指定
 * {@code from}。</p>
 *
 * <pre>{@code
 * @Select
 * @Join(entity = Department.class,
 *       on = @Join.On(local = "departmentId", target = "id"),
 *       type = JoinType.LEFT)
 * List<User> findByDepartmentName(
 *       @Condition(value = "name", entity = Department.class) String departmentName);
 *
 * // 链式连接：companyId 只存在于 Department，from 自动推断为 Department
 * @Select
 * @Join(entity = Department.class,
 *       on = @Join.On(local = "departmentId", target = "id"))
 * @Join(entity = Company.class,
 *       on = @Join.On(local = "companyId", target = "id"))
 * List<User> findByCompanyId(
 *       @Condition(value = "id", entity = Company.class) long companyId);
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
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
     * ON 条件左侧所属实体。
     *
     * <p>缺省（{@code void.class}）时<b>自动推断</b>：取 {@link On#local()} 字段所属的
     * 可用实体（宿主或此前已连接的实体）。链式连接因此无需重复书写本属性；推断有歧义
     * （字段名在多个已连接实体中都存在）时编译报错，需显式指定。显式指定时必须是宿主
     * 实体或当前注解之前已经连接的实体。</p>
     *
     * @return ON 条件左侧实体，或 {@code void.class}（自动推断）
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
    @Retention(RetentionPolicy.CLASS)
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
