package io.github.erdsgfc.postgresql.repository;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;
import io.github.erdsgfc.postgresql.entity.SysAdmin;
import org.springframework.stereotype.Repository;

@Dao
public interface SysAdminRepository extends BaseRepository<SysAdmin, Long> {

}
