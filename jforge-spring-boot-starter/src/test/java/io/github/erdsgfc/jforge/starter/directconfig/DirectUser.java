package io.github.erdsgfc.jforge.starter.directconfig;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

/**
 * Test entity for the element-level config test; this package has no {@code package-info},
 * so any JForge config must come from the repository interface directly.
 */
@Table(name = "direct_users")
public interface DirectUser {

    /** Database-generated primary key. */
    @Id
    @GeneratedValue
    Long id();

    DirectUser id(Long id);

    /** User display name. */
    @Column(name = "user_name")
    String name();

    DirectUser name(String name);

    /** User age. */
    Integer age();

    DirectUser age(Integer age);
}
