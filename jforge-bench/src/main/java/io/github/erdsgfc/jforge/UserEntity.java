package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * 以接口形式声明的测试实体(属性方法 + builder 风格的 setter)。
 * 注解处理器生成 {@code UserEntity_Impl}（{@code UserRepository_Impl} 的 private static final
 * 嵌套类，经 {@code repo.createEntity()} 获取实例）。
 */
@Table(name = "users")
public interface UserEntity {

    /** 数据库生成的主键(BIGSERIAL)。 */
    @Id
    @GeneratedValue
    Long id();

    UserEntity id(Long id);

    /** 用户显示名,映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    UserEntity name(String name);

    /** 用户年龄(岁)。 */
    Integer age();

    UserEntity age(Integer age);
}
