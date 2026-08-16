package io.github.erdsgfc.jforge.clsconfig.sub;

import io.github.erdsgfc.jforge.annotation.GeneratedValue;
import io.github.erdsgfc.jforge.annotation.Id;

/**
 * 子包实体:自身包无配置,沿包链继承父包中 {@link ClsConfig} 类的标注配置。
 */
public interface ClsEntity {

    @Id
    @GeneratedValue
    Long id();

    ClsEntity id(Long id);

    String name();

    ClsEntity name(String name);
}
