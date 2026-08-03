package com.qin.orm.annotation;

/**
 * Determines how a property method name is mapped to a database column name
 * when no {@code @Column} annotation is present.
 */
public enum NamingStrategy {

    /** Keep the method name as-is (e.g. {@code userName()} → column {@code userName}). */
    NONE,

    /** Convert lower camel case to snake case (e.g. {@code userName()} → column {@code user_name}). */
    CAMEL_TO_SNAKE;
}