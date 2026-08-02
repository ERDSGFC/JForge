package com.qin.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.List;

/**
 * Unit tests for {@link MetadataProcessor}: verifies generated source content
 * for valid entities and compile-time error reporting for invalid ones.
 *
 * <p>Because {@code orm-processor} cannot depend on {@code orm} (circular dependency),
 * generated sources referencing {@code GeneratedMetadata} / {@code FieldAccessor}
 * will fail to compile — but we still capture and assert on the source text itself.
 */
class MetadataProcessorTest {

    // ============================================================
    // 正向用例：生成代码内容验证
    // ============================================================

    /** 标准实体：@Id + @GeneratedValue + @Column → 验证生成的完整源码结构。 */
    @Test
    void shouldGenerateMetadataForEntity() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.User", STANDARD_ENTITY, new MetadataProcessor());

        String metadata = result.generatedSources.get("com.qin.orm.generated.User_Metadata");
        assertNotNull(metadata, "should generate User_Metadata");

        // 类结构
        assertTrue(metadata.contains("public final class User_Metadata implements GeneratedMetadata"),
                "should implement GeneratedMetadata");
        assertTrue(metadata.contains("public static final User_Metadata INSTANCE = new User_Metadata()"),
                "should have singleton INSTANCE");

        // 接口方法
        assertTrue(metadata.contains("return User.class"), "entityClass()");
        assertTrue(metadata.contains("return \"users\""), "tableName()");
        assertTrue(metadata.contains("return \"id\""), "idColumn()");
        assertTrue(metadata.contains("return true"), "idGenerated() — should be true");

        // 5 条预生成 SQL
        assertTrue(metadata.contains("INSERT INTO users (user_name,age) VALUES (?,?)"),
                "insertSql — should exclude generated id column");
        assertTrue(metadata.contains("UPDATE users SET user_name=?,age=? WHERE id=?"),
                "updateSql");
        assertTrue(metadata.contains("DELETE FROM users WHERE id=?"),
                "deleteSql");
        assertTrue(metadata.contains("SELECT id,user_name,age FROM users WHERE id=?"),
                "selectByIdSql");
        assertTrue(metadata.contains("SELECT id,user_name,age FROM users"),
                "selectAllSql");

        // accessors: id 字段
        assertTrue(metadata.contains("FieldAccessor.of(\"id\", true, Long.class,"),
                "id accessor: column='id', isId=true, type=Long");
        // accessors: user_name 字段
        assertTrue(metadata.contains("FieldAccessor.of(\"user_name\", false, String.class,"),
                "user_name accessor");
        // accessors: age 字段
        assertTrue(metadata.contains("FieldAccessor.of(\"age\", false, Integer.class,"),
                "age accessor");

        // accessor 中的 getter/setter lambda
        assertTrue(metadata.contains("((User) e).getId()"), "getter lambda");
        assertTrue(metadata.contains("((User) e).setId((Long) v)"), "setter lambda");

        // 注册表
        String registry = result.generatedSources.get("com.qin.orm.generated.GeneratedMetadataRegistry");
        assertNotNull(registry, "should generate GeneratedMetadataRegistry");
        assertTrue(registry.contains("if (entityClass == User.class) return User_Metadata.INSTANCE"),
                "registry should map User → User_Metadata");
    }

    /** 无 @Column 的字段应默认使用字段名作为列名。 */
    @Test
    void shouldUseFieldNameAsColumnNameWhenAnnotationAbsent() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.Item", NO_COLUMN_ENTITY, new MetadataProcessor());

        String metadata = result.generatedSources.get("com.qin.orm.generated.Item_Metadata");
        assertNotNull(metadata);

        // 无 @Column → 列名等于字段名
        assertTrue(metadata.contains("FieldAccessor.of(\"description\", false, String.class,"),
                "column name should default to field name 'description'");
        assertTrue(metadata.contains("INSERT INTO items (description) VALUES (?)"),
                "INSERT should use field name as column name");
        assertTrue(metadata.contains("SELECT id,description FROM items"),
                "SELECT should use field name as column name");
    }

    /** @Id 无 @GeneratedValue 时 INSERT SQL 应包含 id 列（由调用方手动赋值）。 */
    @Test
    void shouldIncludeIdColumnInInsertWhenNotGenerated() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.Manual", MANUAL_ID_ENTITY, new MetadataProcessor());

        String metadata = result.generatedSources.get("com.qin.orm.generated.Manual_Metadata");
        assertNotNull(metadata);

        assertTrue(metadata.contains("return false"), "idGenerated() should be false");
        assertTrue(metadata.contains("INSERT INTO manuals (id,name) VALUES (?,?)"),
                "INSERT should include id column when not generated");
    }

    /** static、transient 关键字标记和 @Transient 注解的字段应在映射中排除。 */
    @Test
    void shouldSkipStaticTransientAndTransientAnnotatedFields() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.WithSkipped", SKIPPED_FIELDS_ENTITY, new MetadataProcessor());

        String metadata = result.generatedSources.get("com.qin.orm.generated.WithSkipped_Metadata");
        assertNotNull(metadata);

        // 应包含的字段
        assertTrue(metadata.contains("FieldAccessor.of(\"id\""));
        assertTrue(metadata.contains("FieldAccessor.of(\"name\""));
        // 应排除的字段
        assertFalse(metadata.contains("\"staticField\""), "static fields should be excluded");
        assertFalse(metadata.contains("\"transientField\""), "transient fields should be excluded");
        assertFalse(metadata.contains("\"ignoredField\""), "@Transient fields should be excluded");
    }

    // ============================================================
    // 异常用例：编译期报错验证
    // ============================================================

    /** 实体没有 @Id → 编译期报错。 */
    @Test
    void shouldErrorWhenNoIdAnnotation() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.NoId", NO_ID_ENTITY, new MetadataProcessor());

        assertFalse(result.success, "compilation should fail");
        assertHasDiagnostic(result, "No @Id field");
    }

    /** 实体有两个 @Id → 编译期报错。 */
    @Test
    void shouldErrorWhenMultipleIdAnnotations() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.DualId", DUAL_ID_ENTITY, new MetadataProcessor());

        assertFalse(result.success, "compilation should fail");
        assertHasDiagnostic(result, "Multiple @Id");
    }

    /** 字段缺少 getter → 编译期报错。 */
    @Test
    void shouldErrorWhenGetterMissing() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.NoGetter", NO_GETTER_ENTITY, new MetadataProcessor());

        assertFalse(result.success, "compilation should fail");
        assertHasDiagnostic(result, "needs a public getter");
    }

    /** 字段缺少 setter → 编译期报错。 */
    @Test
    void shouldErrorWhenSetterMissing() throws Exception {
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.NoSetter", NO_SETTER_ENTITY, new MetadataProcessor());

        assertFalse(result.success, "compilation should fail");
        assertHasDiagnostic(result, "needs a public setter");
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 断言诊断消息中至少有一条包含指定的文本片段。 */
    private static void assertHasDiagnostic(CompilationHelper.CompilationResult result,
                                             String fragment) {
        List<Diagnostic<? extends javax.tools.JavaFileObject>> diags = result.diagnostics;
        boolean found = diags.stream()
                .anyMatch(d -> d.getMessage(null).contains(fragment));
        if (!found) {
            String allMessages = diags.stream()
                    .map(d -> d.getKind() + ": " + d.getMessage(null))
                    .reduce((a, b) -> a + "\n" + b).orElse("(no diagnostics)");
            fail("Expected diagnostic containing '" + fragment + "', but got:\n" + allMessages);
        }
    }

    // ============================================================
    // 测试实体源码（内联，避免文件依赖）
    // ============================================================

    private static final String ANNOTATION_IMPORTS = """
            import com.qin.orm.annotation.Table;
            import com.qin.orm.annotation.Id;
            import com.qin.orm.annotation.GeneratedValue;
            import com.qin.orm.annotation.Column;
            import com.qin.orm.annotation.Transient;
            """;

    /** 标准实体：@Id + @GeneratedValue + @Column。 */
    private static final String STANDARD_ENTITY = """
            package test;
            %s
            @Table(name = "users")
            public class User {
                @Id @GeneratedValue
                private Long id;
                @Column(name = "user_name")
                private String name;
                private Integer age;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public Integer getAge() { return age; }
                public void setAge(Integer age) { this.age = age; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 无 @Column 的实体：字段名 = 列名。 */
    private static final String NO_COLUMN_ENTITY = """
            package test;
            %s
            @Table(name = "items")
            public class Item {
                @Id @GeneratedValue
                private Long id;
                private String description;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getDescription() { return description; }
                public void setDescription(String description) { this.description = description; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** @Id 无 @GeneratedValue：手动赋值 id。 */
    private static final String MANUAL_ID_ENTITY = """
            package test;
            %s
            @Table(name = "manuals")
            public class Manual {
                @Id
                private Long id;
                private String name;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 含 static、transient、@Transient 字段的实体。 */
    private static final String SKIPPED_FIELDS_ENTITY = """
            package test;
            %s
            @Table(name = "skipped")
            public class WithSkipped {
                @Id @GeneratedValue
                private Long id;
                private String name;
                private static String staticField;
                private transient String transientField;
                @Transient
                private String ignoredField;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public String getIgnoredField() { return ignoredField; }
                public void setIgnoredField(String ignoredField) { this.ignoredField = ignoredField; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 缺 @Id 的实体。 */
    private static final String NO_ID_ENTITY = """
            package test;
            %s
            @Table(name = "bad")
            public class NoId {
                private String value;

                public String getValue() { return value; }
                public void setValue(String value) { this.value = value; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 两个 @Id 的实体。 */
    private static final String DUAL_ID_ENTITY = """
            package test;
            %s
            @Table(name = "bad")
            public class DualId {
                @Id
                private Long id1;
                @Id
                private Long id2;

                public Long getId1() { return id1; }
                public void setId1(Long id1) { this.id1 = id1; }
                public Long getId2() { return id2; }
                public void setId2(Long id2) { this.id2 = id2; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 缺 getter 的实体。 */
    private static final String NO_GETTER_ENTITY = """
            package test;
            %s
            @Table(name = "bad")
            public class NoGetter {
                @Id @GeneratedValue
                private Long id;
                private String name;

                // only setter, no getter for 'name'
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public void setName(String name) { this.name = name; }
            }
            """.formatted(ANNOTATION_IMPORTS);

    /** 缺 setter 的实体。 */
    private static final String NO_SETTER_ENTITY = """
            package test;
            %s
            @Table(name = "bad")
            public class NoSetter {
                @Id @GeneratedValue
                private Long id;
                private String name;

                // only getter, no setter for 'name'
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
            }
            """.formatted(ANNOTATION_IMPORTS);
}
