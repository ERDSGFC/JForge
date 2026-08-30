package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 手动主键的基准实体：{@code id} 由调用方赋值（无 {@code @GeneratedValue}）——
 * save 走普通 INSERT（无 RETURN_GENERATED_KEYS、无生成键回写），用于与
 * {@link TimedUserEntity} 的生成键回写路径做减法对比。
 */
@Table(name = "manual_id_users")
public interface ManualIdUser {

    /** 手动赋值的主键。 */
    @Id
    Long id();

    ManualIdUser id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    ManualIdUser name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    ManualIdUser age(Integer age);
}
