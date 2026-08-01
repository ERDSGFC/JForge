package com.qin.orm.core;

import com.qin.orm.OrmException;
import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;
import com.qin.orm.annotation.Transient;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable per-class mapping metadata, parsed once via reflection and cached.
 * Field access goes through {@link MethodHandle}s (no per-call reflection).
 */
public final class EntityMetadata {

    private static final ConcurrentHashMap<Class<?>, EntityMetadata> CACHE = new ConcurrentHashMap<>();

    /** Returns the cached metadata for a class, parsing it on first use. */
    public static EntityMetadata of(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, EntityMetadata::new);
    }

    private final Class<?> entityClass;
    private final String tableName;
    private final List<Field> fields = new ArrayList<>();
    private final List<String> columnNames = new ArrayList<>();
    private final Map<Field, MethodHandle> getters = new ConcurrentHashMap<>();
    private final Map<Field, MethodHandle> setters = new ConcurrentHashMap<>();
    private Field idField;
    private String idColumnName;
    private boolean idGenerated;

    private EntityMetadata(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null) {
            throw new OrmException("@Table is required on " + entityClass.getName());
        }
        this.entityClass = entityClass;
        this.tableName = table.name();

        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            String columnName = column != null ? column.name() : field.getName();
            fields.add(field);
            columnNames.add(columnName);
            getters.put(field, buildGetter(field));
            setters.put(field, buildSetter(field));

            if (field.isAnnotationPresent(Id.class)) {
                if (idField != null) {
                    throw new OrmException("Multiple @Id fields on " + entityClass.getName());
                }
                idField = field;
                idColumnName = columnName;
                idGenerated = field.isAnnotationPresent(GeneratedValue.class);
            }
        }
        if (idField == null) {
            throw new OrmException("No @Id field on " + entityClass.getName());
        }
    }

    private static MethodHandle buildGetter(Field field) {
        try {
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw new OrmException("Cannot access field " + field, e);
        }
    }

    private static MethodHandle buildSetter(Field field) {
        try {
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw new OrmException("Cannot access field " + field, e);
        }
    }

    public Class<?> entityClass() {
        return entityClass;
    }

    public String tableName() {
        return tableName;
    }

    /** Persistent fields, in declaration order (excluding the generated id). */
    public List<Field> fields() {
        return fields;
    }

    /** Column names aligned with {@link #fields()}. */
    public List<String> columnNames() {
        return columnNames;
    }

    public Field idField() {
        return idField;
    }

    public String idColumnName() {
        return idColumnName;
    }

    public boolean idGenerated() {
        return idGenerated;
    }

    /** Columns to include in INSERT (excludes a generated id). */
    public List<String> insertColumns() {
        List<String> result = new ArrayList<>(columnNames.size());
        for (int i = 0; i < fields.size(); i++) {
            if (!(fields.get(i) == idField && idGenerated)) {
                result.add(columnNames.get(i));
            }
        }
        return result;
    }

    /** Columns to include in UPDATE (excludes the id). */
    public List<String> updateColumns() {
        List<String> result = new ArrayList<>(columnNames.size());
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i) != idField) {
                result.add(columnNames.get(i));
            }
        }
        return result;
    }

    public Object getFieldValue(Object entity, Field field) {
        try {
            return getters.get(field).invoke(entity);
        } catch (Throwable e) {
            throw new OrmException("Cannot read field " + field + " on " + entity, e);
        }
    }

    public void setFieldValue(Object entity, Field field, Object value) {
        try {
            setters.get(field).invoke(entity, value);
        } catch (Throwable e) {
            throw new OrmException("Cannot write field " + field + " on " + entity, e);
        }
    }
}
