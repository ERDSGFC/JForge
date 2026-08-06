package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/**
 * Test repository: inherits CRUD from {@link BaseRepository} and adds
 * {@link Query}-annotated custom methods, implemented at compile time.
 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** Full-entity query mapped by column name. */
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    /** Partial-field projection into a DTO record. */
    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);

    /** Scalar count. */
    @Query("SELECT COUNT(*) FROM users WHERE age = :age")
    long countByAge(@Bind("age") int age);

    /** Update statement returning affected rows. */
    @Query("UPDATE users SET age = :age WHERE id = :id")
    int updateAge(@Bind("id") long id, @Bind("age") int age);
}
