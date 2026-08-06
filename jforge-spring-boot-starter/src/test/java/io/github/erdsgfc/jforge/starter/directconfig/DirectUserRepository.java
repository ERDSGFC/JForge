package io.github.erdsgfc.jforge.starter.directconfig;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Element-level configuration: {@code @JForgeConfig(springBeans = true)} is placed
 * directly on the repository interface (no {@code package-info} in this package),
 * verifying the processor resolves configuration from the element itself before the
 * containing package.
 */
@Dao
@JForgeConfig(springBeans = true)
public interface DirectUserRepository extends BaseRepository<DirectUser, Long> {
}
