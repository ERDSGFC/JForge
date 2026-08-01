package com.qin.orm;

import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;

/** Test entity used by the CRUD integration tests. */
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "user_name")
    private String name;

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
