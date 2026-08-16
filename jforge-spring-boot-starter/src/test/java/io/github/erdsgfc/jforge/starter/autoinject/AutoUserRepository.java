package io.github.erdsgfc.jforge.starter.autoinject;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 测试仓库，其生成实现标注了 {@code @Repository}（通过包的
 * {@code @JForgeConfig(springBeans = true)}），因此 Spring 组件扫描会自动将其
 * 注册为 Bean。
 */
@Dao
public interface AutoUserRepository extends BaseRepository<AutoUser, Long> {
}
