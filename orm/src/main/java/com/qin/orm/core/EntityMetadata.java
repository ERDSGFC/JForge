package com.qin.orm.core;

import com.qin.orm.OrmException;
import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;
import com.qin.orm.annotation.Transient;
import com.qin.orm.generated.GeneratedMetadataRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable per-class mapping metadata.
 *
 * Two construction paths:
 * <ul>
 *   <li><b>Generated</b> (preferred, AOT-safe): compile-time metadata from the annotation
 *       processor ({@link GeneratedMetadataRegistry}); field access is a direct
 *       getter/setter call — no reflection, works in GraalVM Native Image.</li>
 *   <li><b>Reflected</b> (fallback, JVM only): runtime reflection + {@link MethodHandle}s.</li>
 * </ul>
 *
 * All per-operation costs (SQL strings, type-exact binders and readers) are resolved once
 * at construction time, whichever path is used.
 */
public final class EntityMetadata {

    private static final ConcurrentHashMap<Class<?>, EntityMetadata> CACHE = new ConcurrentHashMap<>();

    /** Returns the cached metadata for a class, using generated metadata when available. */
    public static EntityMetadata of(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, EntityMetadata::create);
    }

    private static EntityMetadata create(Class<?> entityClass) {
        GeneratedMetadata generated = GeneratedMetadataRegistry.find(entityClass);
        return generated != null ? new EntityMetadata(generated) : new EntityMetadata(entityClass);
    }

    // ==================== Parsing ====================

    /** A single mapped column, resolved once: value access + binder + reader. */
    private static final class ColumnDef {
        final String name;
        final boolean isId;
        final Getter get;
        final Setter set;
        final ParamBinder binder;
        final RowReader reader;

        ColumnDef(String name, boolean isId, Getter get, Setter set, ParamBinder binder, RowReader reader) {
            this.name = name;
            this.isId = isId;
            this.get = get;
            this.set = set;
            this.binder = binder;
            this.reader = reader;
        }
    }

    @FunctionalInterface
    private interface Getter {
        Object get(Object entity);
    }

    @FunctionalInterface
    private interface Setter {
        void set(Object entity, Object value);
    }

    /** Parsed result shared by both construction paths. */
    private static final class Parsed {
        String tableName;
        final List<ColumnDef> columns = new ArrayList<>();
        int idIndex = -1;
        boolean idGenerated;
    }

    private static Parsed parseByReflection(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null) {
            throw new OrmException("@Table is required on " + entityClass.getName());
        }
        Parsed parsed = new Parsed();
        parsed.tableName = table.name();

        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            String columnName = column != null ? column.name() : field.getName();
            boolean isId = field.isAnnotationPresent(Id.class);
            if (isId) {
                if (parsed.idIndex >= 0) {
                    throw new OrmException("Multiple @Id fields on " + entityClass.getName());
                }
                parsed.idIndex = parsed.columns.size();
                parsed.idGenerated = field.isAnnotationPresent(GeneratedValue.class);
            }
            parsed.columns.add(new ColumnDef(columnName, isId,
                    wrapGetter(buildGetter(field)), wrapSetter(buildSetter(field)),
                    binderFor(field.getType()), readerFor(field.getType())));
        }
        if (parsed.idIndex < 0) {
            throw new OrmException("No @Id field on " + entityClass.getName());
        }
        return parsed;
    }

    private static Parsed parseFromGenerated(GeneratedMetadata generated) {
        Parsed parsed = new Parsed();
        parsed.tableName = generated.tableName();
        parsed.idGenerated = generated.idGenerated();
        List<FieldAccessor> accessors = generated.accessors();
        for (int i = 0; i < accessors.size(); i++) {
            FieldAccessor accessor = accessors.get(i);
            if (accessor.isId()) {
                if (parsed.idIndex >= 0) {
                    throw new OrmException("Multiple @Id accessors in " + generated.getClass().getName());
                }
                parsed.idIndex = i;
            }
            parsed.columns.add(new ColumnDef(accessor.columnName(), accessor.isId(),
                    accessor::get, accessor::set,
                    binderFor(accessor.fieldType()), readerFor(accessor.fieldType())));
        }
        if (parsed.idIndex < 0) {
            throw new OrmException("Generated metadata without @Id: " + generated.getClass().getName());
        }
        return parsed;
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

    private static Getter wrapGetter(MethodHandle handle) {
        return entity -> {
            try {
                return handle.invoke(entity);
            } catch (Throwable e) {
                throw new OrmException("Cannot read field via " + handle, e);
            }
        };
    }

    private static Setter wrapSetter(MethodHandle handle) {
        return (entity, value) -> {
            try {
                handle.invoke(entity, value);
            } catch (Throwable e) {
                throw new OrmException("Cannot write field via " + handle, e);
            }
        };
    }

    /** Picks the type-exact parameter binder for a field type (fallback: setObject). */
    private static ParamBinder binderFor(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return (ps, i, v) -> ps.setLong(i, (Long) v);
        }
        if (type == int.class || type == Integer.class) {
            return (ps, i, v) -> ps.setInt(i, (Integer) v);
        }
        if (type == String.class) {
            return (ps, i, v) -> ps.setString(i, (String) v);
        }
        if (type == boolean.class || type == Boolean.class) {
            return (ps, i, v) -> ps.setBoolean(i, (Boolean) v);
        }
        if (type == double.class || type == Double.class) {
            return (ps, i, v) -> ps.setDouble(i, (Double) v);
        }
        if (type == float.class || type == Float.class) {
            return (ps, i, v) -> ps.setFloat(i, (Float) v);
        }
        if (type == short.class || type == Short.class) {
            return (ps, i, v) -> ps.setShort(i, (Short) v);
        }
        if (type == byte.class || type == Byte.class) {
            return (ps, i, v) -> ps.setByte(i, (Byte) v);
        }
        if (type == Timestamp.class) {
            return (ps, i, v) -> ps.setTimestamp(i, (Timestamp) v);
        }
        if (type == java.sql.Date.class) {
            return (ps, i, v) -> ps.setDate(i, (java.sql.Date) v);
        }
        if (type == BigDecimal.class) {
            return (ps, i, v) -> ps.setBigDecimal(i, (BigDecimal) v);
        }
        if (type == byte[].class) {
            return (ps, i, v) -> ps.setBytes(i, (byte[]) v);
        }
        return (ps, i, v) -> ps.setObject(i, v); // LocalDate/LocalDateTime/enums etc.
    }

    /** Picks the type-exact row reader for a field type (fallback: getObject). */
    private static RowReader readerFor(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return (rs, c) -> {
                long v = rs.getLong(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == int.class || type == Integer.class) {
            return (rs, c) -> {
                int v = rs.getInt(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == String.class) {
            return (rs, c) -> rs.getString(c);
        }
        if (type == boolean.class || type == Boolean.class) {
            return (rs, c) -> {
                boolean v = rs.getBoolean(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == double.class || type == Double.class) {
            return (rs, c) -> {
                double v = rs.getDouble(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == float.class || type == Float.class) {
            return (rs, c) -> {
                float v = rs.getFloat(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == short.class || type == Short.class) {
            return (rs, c) -> {
                short v = rs.getShort(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == byte.class || type == Byte.class) {
            return (rs, c) -> {
                byte v = rs.getByte(c);
                return rs.wasNull() ? null : v;
            };
        }
        if (type == BigDecimal.class) {
            return (rs, c) -> rs.getBigDecimal(c);
        }
        if (type == Timestamp.class) {
            return (rs, c) -> rs.getTimestamp(c);
        }
        if (type == java.sql.Date.class) {
            return (rs, c) -> rs.getDate(c);
        }
        if (type == LocalDate.class) {
            return (rs, c) -> rs.getObject(c, LocalDate.class);
        }
        if (type == LocalDateTime.class) {
            return (rs, c) -> rs.getObject(c, LocalDateTime.class);
        }
        if (type == byte[].class) {
            return (rs, c) -> rs.getBytes(c);
        }
        return (rs, c) -> rs.getObject(c);
    }

    // ==================== State ====================

    private final Class<?> entityClass;
    private final String tableName;
    private final List<ColumnDef> columns;
    private final int idIndex;
    private final boolean idGenerated;
    private final List<String> columnNames;
    private final List<RowReader> rowReaders;

    // Pre-generated SQL.
    private final String insertSql;
    private final String updateSql;
    private final String deleteSql;
    private final String selectByIdSql;
    private final String selectAllSql;

    private EntityMetadata(GeneratedMetadata generated) {
        this(generated.entityClass(), parseFromGenerated(generated), generated);
    }

    private EntityMetadata(Class<?> entityClass) {
        this(entityClass, parseByReflection(entityClass), null);
    }

    private EntityMetadata(Class<?> entityClass, Parsed parsed, GeneratedMetadata generated) {
        this.entityClass = entityClass;
        this.tableName = parsed.tableName;
        this.columns = parsed.columns;
        this.idIndex = parsed.idIndex;
        this.idGenerated = parsed.idGenerated;

        this.columnNames = new ArrayList<>(columns.size());
        for (ColumnDef column : columns) {
            columnNames.add(column.name);
        }
        this.rowReaders = new ArrayList<>(columns.size());
        for (ColumnDef column : columns) {
            rowReaders.add(column.reader);
        }

        if (generated != null) {
            // Pre-generated SQL from the compile-time metadata.
            this.insertSql = generated.insertSql();
            this.updateSql = generated.updateSql();
            this.deleteSql = generated.deleteSql();
            this.selectByIdSql = generated.selectByIdSql();
            this.selectAllSql = generated.selectAllSql();
        } else {
            // Build once at parse time.
            List<String> insertColumns = new ArrayList<>();
            List<String> updateColumns = new ArrayList<>();
            for (ColumnDef column : columns) {
                if (!(column.isId && idGenerated)) {
                    insertColumns.add(column.name);
                }
                if (!column.isId) {
                    updateColumns.add(column.name);
                }
            }
            this.insertSql = SqlGenerator.insertSql(tableName, insertColumns);
            this.updateSql = SqlGenerator.updateSql(tableName, updateColumns, parsed.columns.get(idIndex).name);
            this.deleteSql = SqlGenerator.deleteSql(tableName, parsed.columns.get(idIndex).name);
            this.selectByIdSql = SqlGenerator.selectByIdSql(tableName, columnNames, parsed.columns.get(idIndex).name);
            this.selectAllSql = SqlGenerator.selectAllSql(tableName, columnNames);
        }
    }

    // ==================== SQL (pre-generated) ====================

    public String insertSql() {
        return insertSql;
    }

    public String updateSql() {
        return updateSql;
    }

    public String deleteSql() {
        return deleteSql;
    }

    public String selectByIdSql() {
        return selectByIdSql;
    }

    public String selectAllSql() {
        return selectAllSql;
    }

    // ==================== Binding ====================

    /** Binds non-generated columns of the entity to the INSERT placeholders. */
    public void bindInsertParams(PreparedStatement ps, Object entity) throws SQLException {
        int index = 1;
        for (ColumnDef column : columns) {
            if (column.isId && idGenerated) {
                continue;
            }
            column.binder.bind(ps, index++, column.get.get(entity));
        }
    }

    /** Binds non-id columns, then the id, to the UPDATE placeholders. */
    public void bindUpdateParams(PreparedStatement ps, Object entity) throws SQLException {
        int index = 1;
        for (ColumnDef column : columns) {
            if (!column.isId) {
                column.binder.bind(ps, index++, column.get.get(entity));
            }
        }
        ColumnDef id = columns.get(idIndex);
        id.binder.bind(ps, index, id.get.get(entity));
    }

    /** Binds the id to the WHERE placeholder (DELETE/SELECT by id). */
    public void bindId(PreparedStatement ps, Object id) throws SQLException {
        columns.get(idIndex).binder.bind(ps, 1, id);
    }

    // ==================== Value access ====================

    public Object getValue(Object entity, int columnIndex) {
        return columns.get(columnIndex).get.get(entity);
    }

    public void setValue(Object entity, int columnIndex, Object value) {
        columns.get(columnIndex).set.set(entity, value);
    }

    public Object getIdValue(Object entity) {
        return columns.get(idIndex).get.get(entity);
    }

    public void setIdValue(Object entity, Object value) {
        columns.get(idIndex).set.set(entity, value);
    }

    // ==================== Metadata ====================

    public Class<?> entityClass() {
        return entityClass;
    }

    public String tableName() {
        return tableName;
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public String idColumnName() {
        return columns.get(idIndex).name;
    }

    public boolean idGenerated() {
        return idGenerated;
    }

    /** Row readers aligned with {@link #columnNames()}, for ResultSet → entity mapping. */
    public List<RowReader> rowReaders() {
        return rowReaders;
    }
}
