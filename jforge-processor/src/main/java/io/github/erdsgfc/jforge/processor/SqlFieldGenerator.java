package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.FieldSpec;
import io.github.erdsgfc.jforge.annotation.Query;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成仓库 impl 类的固定 SQL 常量字段（{@code private final String xxxSql = "..."}）。
 *
 * <p>纯静态、无状态：CRUD 各方法的 SQL + 每个 {@code @Query} 方法的 SQL。IN 查询
 * （findByIds/deleteByIds）只把固定前缀提取为常量，占位符部分仍在方法内动态拼接。</p>
 */
final class SqlFieldGenerator {

    private SqlFieldGenerator() {
    }

    /**
     * 收集仓库类的固定 SQL 常量字段：CRUD 各方法的 SQL + 每个 {@code @Query} 方法的 SQL。
     *
     * @param info the parsed repository info
     * @return the SQL constant field specifications
     */
    static List<FieldSpec> sqlFields(JForgeProcessor.DaoInfo info) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(sqlField("saveSql", saveSql(info)));
        fields.add(sqlField("deleteByIdSql", deleteByIdSql(info)));
        fields.add(sqlField("deleteByIdsBaseSql", deleteByIdsBaseSql(info)));
        fields.add(sqlField("updateSql", updateSql(info)));
        fields.add(sqlField("findByIdSql", findByIdSql(info)));
        fields.add(sqlField("findByIdsBaseSql", findByIdsBaseSql(info)));
        fields.add(sqlField("findAllSql", findAllSql(info)));
        fields.add(sqlField("countSql", countSql(info)));
        fields.add(sqlField("countByIdSql", countByIdSql(info)));
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                if (method.getAnnotation(Query.class) != null) {
                    fields.add(sqlField(method.getSimpleName() + "Sql", querySql(method)));
                }
            }
        }
        return fields;
    }

    /** 构造一个 {@code private final String name = "sql";} 字段。 */
    private static FieldSpec sqlField(String name, String sql) {
        return FieldSpec.builder(String.class, name, Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$S", sql)
                .build();
    }

    static String saveSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        return "INSERT INTO " + model.tableName() + " ("
                + SqlCodegen.joinColumns(SqlCodegen.namesOf(insertColumns)) + ") VALUES ("
                + SqlCodegen.placeholders(insertColumns.size()) + ")";
    }

    static String deleteByIdSql(JForgeProcessor.DaoInfo info) {
        return "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
    }

    static String deleteByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        return "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + " IN (";
    }

    static String updateSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        StringBuilder sets = new StringBuilder();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!column.isId) {
                if (sets.length() > 0) {
                    sets.append(",");
                }
                sets.append(column.columnName).append("=?");
            }
        }
        return "UPDATE " + model.tableName() + " SET " + sets + " WHERE "
                + model.idColumn().columnName + "=?";
    }

    static String findByIdSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?";
    }

    static String findByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + " IN (";
    }

    static String findAllSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.namesOf(info.model.columns())) + " FROM "
                + info.model.tableName();
    }

    static String countSql(JForgeProcessor.DaoInfo info) {
        return "SELECT COUNT(*) FROM " + info.model.tableName();
    }

    static String countByIdSql(JForgeProcessor.DaoInfo info) {
        return "SELECT COUNT(*) FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
    }

    /** {@code @Query} 方法的 SQL：命名占位符转 {@code ?}（返回转换后的字符串）。 */
    static String querySql(ExecutableElement method) {
        return SqlCodegen.convertPlaceholders(method.getAnnotation(Query.class).value(), new ArrayList<>());
    }
}
