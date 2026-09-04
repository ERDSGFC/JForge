package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/** 只读实体的仓库：验证无 setter 时行映射仍完整工作。 */
@Dao
public interface ReadonlyUserRepository extends BaseRepository<ReadonlyUser, Long> {

    /** 按年龄过滤查询（列名映射）。 */
    @Query("SELECT id, user_name, age FROM readonly_users WHERE age > :age")
    List<ReadonlyUser> findByAgeGreaterThan(@Bind int age);
}
