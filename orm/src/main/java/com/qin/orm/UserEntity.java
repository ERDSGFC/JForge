package com.qin.orm;

import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;

/** Test entity used by the CRUD integration tests and the ORM vs JDBC benchmark. */
@Table(name = "users")
public class UserEntity {

    /** Database-generated primary key (BIGSERIAL). */
    @Id
    @GeneratedValue
    private Long id;

    /** User display name, mapped to the {@code user_name} column. */
    @Column(name = "user_name")
    private String name;

    /** User age in years, mapped to the {@code age} column. */
    private Integer age;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }
}
