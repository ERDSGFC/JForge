package io.github.erdsgfc.jforge.starter;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * starter 模块的测试实体：一个包含属性方法与 builder 风格 setter 的接口，
 * 由 ORM 处理器编译为 {@code TestUser_Impl}（仓库 impl 的 private static final 嵌套类）。
 */
@Table(name = "test_users")
public interface TestUser {

    /** 数据库生成的主键（BIGSERIAL）。 */
    @Id
    @GeneratedValue
    Long id();

    TestUser id(Long id);

    /** 用户显示名，映射到 {@code user_name} 列。 */
    @Column(name = "user_name")
    String name();

    TestUser name(String name);

    /** 用户年龄（岁）。 */
    Integer age();

    TestUser age(Integer age);
}