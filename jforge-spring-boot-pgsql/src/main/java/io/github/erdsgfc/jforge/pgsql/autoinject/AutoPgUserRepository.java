package io.github.erdsgfc.jforge.pgsql.autoinject;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** Spring 自动注入仓库（{@code springBeans = true} → 生成的 impl 是 {@code @Repository} bean）。 */
@Dao
public interface AutoPgUserRepository extends BaseRepository<AutoPgUser, Long> {
}
