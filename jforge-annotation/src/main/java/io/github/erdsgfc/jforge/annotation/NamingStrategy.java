package io.github.erdsgfc.jforge.annotation;

/**
 * 决定在没有 {@code @Column} 注解时，属性方法名如何映射为数据库列名。
 */
public enum NamingStrategy {

    /** 方法名原样保留（例如 {@code userName()} → 列 {@code userName}）。 */
    NONE,

    /** 将小驼峰转换为蛇形命名（例如 {@code userName()} → 列 {@code user_name}）。 */
    CAMEL_TO_SNAKE;
}