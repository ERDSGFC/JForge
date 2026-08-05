package com.qin.orm.starter;

import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;

/**
 * Test entity for the starter module: an interface with property methods and
 * builder-style setters, compiled into {@code TestUser_Impl} by the ORM processor.
 */
@Table(name = "test_users")
public interface TestUser {

    /** Database-generated primary key (BIGSERIAL). */
    @Id
    @GeneratedValue
    Long id();

    TestUser id(Long id);

    /** User display name, mapped to the {@code user_name} column. */
    @Column(name = "user_name")
    String name();

    TestUser name(String name);

    /** User age in years. */
    Integer age();

    TestUser age(Integer age);
}