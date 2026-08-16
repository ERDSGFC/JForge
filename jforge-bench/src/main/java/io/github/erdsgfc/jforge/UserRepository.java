package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/**
 * 测试仓库:继承 {@link BaseRepository} 的 CRUD,并添加
 * 标注 {@link Query} 的自定义方法,实现由编译期生成。
 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** 按列名映射的完整实体查询。 */
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    /** 部分字段投影到 DTO record。 */
    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);

    /** 标量计数。 */
    @Query("SELECT COUNT(*) FROM users WHERE age = :age")
    long countByAge(@Bind("age") int age);

    /** 返回受影响行数的更新语句。 */
    @Query("UPDATE users SET age = :age WHERE id = :id")
    int updateAge(@Bind("id") long id, @Bind("age") int age);
}
