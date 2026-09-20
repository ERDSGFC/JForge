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
 * <b>同一实体可以连接多次</b>（自连接、同表多角色），但每次都<b>必须</b>给
 * {@link #alias()}——因为实体类型无法区分是哪一次连接；无别名时同一实体只能连接一次。</p>
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
 *
 * // 自连接：同一实体两次，别名区分；条件按别名定位
 * @Select
 * @Join(entity = User.class, alias = "manager",
 *       on = @Join.On(local = "managerId", target = "id"))
 * @Join(entity = User.class, alias = "boss", fromAlias = "manager",
 *       on = @Join.On(local = "managerId", target = "id"))
 * List<User> findByBossName(@Condition(entityAlias = "boss", value = "name") String bossName);
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
     * 本次连接的别名——生成的 SQL 形如 {@code JOIN users AS manager ON ...}，
     * 列限定也改用别名（{@code manager.id}）。
     *
     * <p><b>何时需要</b>：同一实体在查询中出现多次（自连接、或同表多角色）时，
     * 实体的 {@code Class} 无法区分是哪一次连接——此时必须给每次连接起不同别名。
     * 缺省（空串）表示不加别名：列直接以表名限定，行为与不写本属性时一致。</p>
     *
     * <p>别名在同一查询内必须唯一；与实体自身的限定名冲突时同样报错。
     * 别名可用于 {@link #fromAlias()}（右侧连接引用本次连接）与
     * {@code @Condition.entityAlias}（条件字段归属本次连接）。</p>
     *
     * @return 本连接的别名；空串 = 不加别名
     */
    String alias() default "";

    /**
     * ON 条件左侧所属连接的<b>别名</b>——用于按别名引用某一次连接。
     *
     * <p>与 {@link #from()} 二选一，不可同时指定：{@code from} 按实体类型引用
     * （适用于每种实体只连接一次的常规场景），{@code fromAlias} 按别名引用
     * （适用于自连接/同表多次连接）。两者都缺省时从 {@link On#local()} 字段推断。</p>
     *
     * @return 左侧连接的别名；空串 = 不使用别名引用
     */
    String fromAlias() default "";

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
