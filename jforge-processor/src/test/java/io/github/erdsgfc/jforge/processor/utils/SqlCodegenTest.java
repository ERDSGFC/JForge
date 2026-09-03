package io.github.erdsgfc.jforge.processor.utils;

import io.github.erdsgfc.jforge.processor.dialect.H2Dialect;
import io.github.erdsgfc.jforge.processor.dialect.MySqlDialect;
import io.github.erdsgfc.jforge.processor.dialect.PostgreSqlDialect;
import io.github.erdsgfc.jforge.processor.dialect.SqliteDialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCodegenTest {

    @Test
    void convertsNamedPlaceholdersAndKeepsRepeatedOrder() {
        SqlCodegen.PlaceholderResult result = SqlCodegen.parsePlaceholders(
                "where id = :id or parent_id = :id", new PostgreSqlDialect());

        assertEquals("where id = ? or parent_id = ?", result.sql());
        assertEquals(List.of("id", "id"), result.names());
        assertEquals(0, result.explicitQuestionMarks());
    }

    @Test
    void countsOriginalQuestionMarksInTheSameScan() {
        SqlCodegen.PlaceholderResult result = SqlCodegen.parsePlaceholders(
                "where id = ? and name = :name and note = '?'", new PostgreSqlDialect());

        assertEquals("where id = ? and name = ? and note = '?'", result.sql());
        assertEquals(List.of("name"), result.names());
        assertEquals(1, result.explicitQuestionMarks());
    }

    @Test
    void postgresSyntaxIsIgnoredOnlyForPostgresLikeDialects() {
        SqlCodegen.PlaceholderResult postgresResult = SqlCodegen.parsePlaceholders(
                "select $tag$ ':inside' :inside $tag$, value::jsonb, :id", new PostgreSqlDialect());

        assertEquals("select $tag$ ':inside' :inside $tag$, value::jsonb, ?", postgresResult.sql());
        assertEquals(List.of("id"), postgresResult.names());

        SqlCodegen.PlaceholderResult h2Result = SqlCodegen.parsePlaceholders(
                "select $tag$ :inside $tag$, value::jsonb, :id", new H2Dialect());

        assertEquals("select $tag$ :inside $tag$, value::jsonb, ?", h2Result.sql());
        assertEquals(List.of("id"), h2Result.names());
    }

    @Test
    void backtickIdentifiersAreDialectSpecific() {
        SqlCodegen.PlaceholderResult mysql = SqlCodegen.parsePlaceholders(
                "select `:column`, :id", new MySqlDialect());
        assertEquals("select `:column`, ?", mysql.sql());
        assertEquals(List.of("id"), mysql.names());

        SqlCodegen.PlaceholderResult sqlite = SqlCodegen.parsePlaceholders(
                "select `:column`, :id", new SqliteDialect());
        assertEquals("select `:column`, ?", sqlite.sql());
        assertEquals(List.of("id"), sqlite.names());
    }

    @Test
    void stringsIdentifiersAndCommentsAreNeverConverted() {
        SqlCodegen.PlaceholderResult result = SqlCodegen.parsePlaceholders(
                "select ':string', \"quoted :identifier\", -- :line\n value /* :block */, :id",
                new PostgreSqlDialect());

        assertEquals("select ':string', \"quoted :identifier\", -- :line\n value /* :block */, ?",
                result.sql());
        assertEquals(List.of("id"), result.names());
    }

    @Test
    void postgresDoubleQuestionMarkIsNotAParameter() {
        SqlCodegen.PlaceholderResult postgres = SqlCodegen.parsePlaceholders(
                "where attributes ?? 'active' and id = :id", new PostgreSqlDialect());
        assertEquals("where attributes ?? 'active' and id = ?", postgres.sql());
        assertEquals(List.of("id"), postgres.names());
        assertEquals(0, postgres.explicitQuestionMarks());

        SqlCodegen.PlaceholderResult mysql = SqlCodegen.parsePlaceholders(
                "where value ?? value and id = ?", new MySqlDialect());
        assertEquals("where value ?? value and id = ?", mysql.sql());
        assertEquals(3, mysql.explicitQuestionMarks());
    }
}
