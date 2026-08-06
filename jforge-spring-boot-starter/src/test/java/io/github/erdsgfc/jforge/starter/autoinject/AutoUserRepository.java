package io.github.erdsgfc.jforge.starter.autoinject;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Test repository whose generated impl is annotated {@code @Repository} (via the
 * package's {@code @JForgeConfig(springBeans = true)}), so Spring component scanning
 * auto-registers it as a bean.
 */
@Dao
public interface AutoUserRepository extends BaseRepository<AutoUser, Long> {
}
