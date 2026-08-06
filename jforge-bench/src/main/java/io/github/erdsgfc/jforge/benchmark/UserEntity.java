package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Column;
import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;
import io.github.erdsgfc.jforge.annotation.Table;

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
