package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * "部分只读"实体：{@code id} 由数据库生成且只读（接口不写 {@code id(Long)} setter），
 * {@code name}/{@code age} 可写。save 后生成的主键经强转到嵌套类的方式回写
 * （接口无 setter，回写调用私有填充 setter）。
 */
@Table(name = "auto_id_users")
public interface AutoIdUser {

    /** 数据库生成的主键（BIGSERIAL）——只读：接口没有 setter。 */
    @Id
    @GeneratedValue
    Long id();

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    AutoIdUser name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    AutoIdUser age(Integer age);
}
