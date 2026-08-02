package com.qin.orm.benchmark;

import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;

/** Benchmark entity declared as an interface (generated impl: {@code UserEntity_Impl}). */
@Table(name = "users")
public interface UserEntity {

    @Id
    @GeneratedValue
    Long id();

    UserEntity id(Long id);

    @Column(name = "user_name")
    String name();

    UserEntity name(String name);

    Integer age();

    UserEntity age(Integer age);
}
