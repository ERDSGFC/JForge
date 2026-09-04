package io.github.erdsgfc.jforge.inherit;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/** 继承实体的仓库：验证父接口属性参与 CRUD 与 {@code @Query} 行映射。 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** 按年龄过滤查询（父接口的 name 属性按列名映射；实体新增列需同步 SELECT）。 */
    @Query("SELECT id, user_name, age, created_at FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind int age);
}
