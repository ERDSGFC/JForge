package com.qin.orm;

import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;

/**
 * Test entity declared as an interface (property methods + builder-style setters).
 * The annotation processor generates {@code UserEntity_Impl}.
 */
@Table(name = "users")
public interface UserEntity {

    /** Database-generated primary key (BIGSERIAL). */
    @Id
    @GeneratedValue
    Long id();

    UserEntity id(Long id);

    /** User display name, mapped to the {@code user_name} column. */
    @Column(name = "user_name")
    String name();

    UserEntity name(String name);

    /** User age in years. */
    Integer age();

    UserEntity age(Integer age);
}
