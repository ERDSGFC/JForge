package io.github.erdsgfc.jforge.starter.autoinject;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * Test entity for the Spring auto-injection test; the package is configured with
 * {@code @JForgeConfig(springBeans = true)} so the generated impl is a Spring bean.
 */
@Table(name = "auto_users")
public interface AutoUser {

    /** Database-generated primary key. */
    @Id
    @GeneratedValue
    Long id();

    AutoUser id(Long id);

    /** User display name. */
    @Column(name = "user_name")
    String name();

    AutoUser name(String name);

    /** User age. */
    Integer age();

    AutoUser age(Integer age);
}
