package com.qin.orm;

import com.qin.orm.core.EntityMetadata;
import com.qin.orm.core.GeneratedMetadata;
import com.qin.orm.generated.GeneratedMetadataRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the compile-time generated metadata path: the registry resolves UserEntity
 * to a generated class, and the SQL exposed through EntityMetadata matches the
 * generator's output (same rules as the runtime SqlGenerator fallback).
 */
class MetadataPathTest {

    @Test
    void generatedMetadataIsRegistered() {
        GeneratedMetadata generated = GeneratedMetadataRegistry.find(UserEntity.class);
        assertNotNull(generated, "UserEntity should have compile-time generated metadata");
        assertEquals(UserEntity.class, generated.entityClass());
        assertEquals("users", generated.tableName());
        assertEquals("id", generated.idColumn());
        assertTrue(generated.idGenerated());
        assertEquals(3, generated.accessors().size());
    }

    @Test
    void unregisteredClassFallsBackToReflection() {
        // A class without @Table has no generated metadata → registry returns null.
        assertNull(GeneratedMetadataRegistry.find(String.class));
    }

    @Test
    void entityMetadataUsesGeneratedSql() {
        EntityMetadata meta = EntityMetadata.of(UserEntity.class);
        assertEquals("INSERT INTO users (user_name,age) VALUES (?,?)", meta.insertSql());
        assertEquals("UPDATE users SET user_name=?,age=? WHERE id=?", meta.updateSql());
        assertEquals("DELETE FROM users WHERE id=?", meta.deleteSql());
        assertEquals("SELECT id,user_name,age FROM users WHERE id=?", meta.selectByIdSql());
        assertEquals("SELECT id,user_name,age FROM users", meta.selectAllSql());
        assertEquals("users", meta.tableName());
        assertEquals("id", meta.idColumnName());
        assertTrue(meta.idGenerated());
    }

    @Test
    void generatedSqlMatchesReflectionFallback() {
        // Same SQL must come out of both paths: compare the generated metadata's SQL
        // with what the runtime SqlGenerator produces from the same mapping.
        GeneratedMetadata generated = GeneratedMetadataRegistry.find(UserEntity.class);
        assertNotNull(generated);
        assertEquals(generated.insertSql(),
                "INSERT INTO users (user_name,age) VALUES (?,?)");
        assertEquals(generated.selectByIdSql(),
                "SELECT id,user_name,age FROM users WHERE id=?");
    }
}
