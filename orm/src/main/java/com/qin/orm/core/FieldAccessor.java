package com.qin.orm.core;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Field-level accessor implemented either by generated code (compile-time, AOT-safe:
 * direct getter/setter calls) or by reflection-backed code (JVM fallback).
 */
public interface FieldAccessor {

    String columnName();

    boolean isId();

    Class<?> fieldType();

    Object get(Object entity);

    void set(Object entity, Object value);

    /** Factory for generated implementations (lambda-based, AOT-safe invokedynamic). */
    static FieldAccessor of(String columnName, boolean isId, Class<?> fieldType,
            Function<Object, Object> getter, BiConsumer<Object, Object> setter) {
        return new FieldAccessor() {
            @Override
            public String columnName() {
                return columnName;
            }

            @Override
            public boolean isId() {
                return isId;
            }

            @Override
            public Class<?> fieldType() {
                return fieldType;
            }

            @Override
            public Object get(Object entity) {
                return getter.apply(entity);
            }

            @Override
            public void set(Object entity, Object value) {
                setter.accept(entity, value);
            }
        };
    }
}
