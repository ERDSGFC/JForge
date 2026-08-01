package com.qin.orm.core;

import java.util.List;

/**
 * Mapping metadata produced at compile time by the annotation processor.
 * Implementations are generated for each {@code @Table} entity and expose pre-built
 * SQL strings plus field accessors (direct getter/setter calls — no reflection,
 * GraalVM Native Image safe).
 */
public interface GeneratedMetadata {

    /** The mapped entity class. */
    Class<?> entityClass();

    /** Creates a new entity instance (direct constructor call in generated code). */
    Object newInstance();

    String tableName();

    String idColumn();

    boolean idGenerated();

    String insertSql();

    String updateSql();

    String deleteSql();

    String selectByIdSql();

    String selectAllSql();

    /** Accessors in field declaration order. */
    List<FieldAccessor> accessors();
}
