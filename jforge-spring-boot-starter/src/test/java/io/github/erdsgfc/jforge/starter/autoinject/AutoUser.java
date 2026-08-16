package io.github.erdsgfc.jforge.starter.autoinject;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * Spring 自动注入测试的测试实体；该包配置了
 * {@code @JForgeConfig(springBeans = true)}，因此生成的实现是 Spring Bean。
 */
@Table(name = "auto_users")
public interface AutoUser {

    /** 数据库生成的主键。 */
    @Id
    @GeneratedValue
    Long id();

    AutoUser id(Long id);

    /** 用户显示名。 */
    @Column(name = "user_name")
    String name();

    AutoUser name(String name);

    /** 用户年龄。 */
    Integer age();

    AutoUser age(Integer age);
}
