package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.FieldSpec;
import io.github.erdsgfc.jforge.annotation.DialectSupport;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成仓库 impl 类的固定 SQL 常量字段（{@code private final String xxxSql = "..."}）。
 *
 * <p>纯静态、无状态：CRUD 各方法的 SQL + 每个 {@code @Query} 方法的 SQL。IN 查询
 * （findByIds/deleteByIds）只把固定前缀提取为常量，占位符部分仍在方法内动态拼接。</p>
 */
public final class SqlFieldGenerator {

    private SqlFieldGenerator() {
    }

    /**
     * 收集仓库类的固定 SQL 常量字段：CRUD 各方法的 SQL + 每个 {@code @Query} 方法的 SQL。
     *
     * @param info the parsed repository info
     * @return the SQL constant field specifications
     */
    public static List<FieldSpec> sqlFields(JForgeProcessor.DaoInfo info, JForgeConfigHelper configHelper) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(sqlField("saveSql", saveSql(info, configHelper)));
        fields.add(sqlField("saveAllSql", saveAllSql(info)));
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
                if (method.getAnnotation(Query.class) != null
                        && !QueryGenerator.hasDynamicWhere(method)) {
                    // 动态 WHERE 查询的 SQL 运行时拼接,不生成常量字段。
                    fields.add(sqlField(method.getSimpleName() + "Sql", querySql(info, method)));
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

    static String saveSql(JForgeProcessor.DaoInfo info, JForgeConfigHelper configHelper) {
        String sql = insertSql(info);
        // PG/SQLite 方言:生成主键走 INSERT ... RETURNING(单语句拿 id,优于 getGeneratedKeys);
        // H2/MySQL 方言走 JDBC 标准路径。
        DialectSupport dialect = configHelper.dialectSupport(info.element);
        if (info.model.idGenerated() && dialect.supportsReturningKeys()) {
            sql += " RETURNING " + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName);
        }
        return sql;
    }

    /**
     * 批量 save 的 SQL:与 {@link #saveSql} 相同但永不含 {@code RETURNING}——批量生成键
     * 回写依赖驱动的 {@code getGeneratedKeys},带 RETURNING 的批量结果读取在各驱动间
     * 差异大,统一走 JDBC 标准。
     */
    static String saveAllSql(JForgeProcessor.DaoInfo info) {
        return insertSql(info);
    }

    private static String insertSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        DialectSupport dialect = model.dialectSupport();
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        return "INSERT INTO " + SqlCodegen.quoteIdentifier(dialect, model.tableName()) + " ("
                + SqlCodegen.joinColumns(SqlCodegen.quotedNames(insertColumns, dialect)) + ") VALUES ("
                + SqlCodegen.placeholders(insertColumns.size()) + ")";
    }

    static String deleteByIdSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "DELETE FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName()) + " WHERE "
                + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName) + "=?";
    }

    static String deleteByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "DELETE FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName()) + " WHERE "
                + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName) + " IN (";
    }

    static String updateSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        DialectSupport dialect = model.dialectSupport();
        StringBuilder sets = new StringBuilder();
        for (EntityModel.ColumnModel column : model.columns()) {
            // SET 排除 id、纯只读列(无值来源,由数据库维护)与 INSERT_ONLY/NONE 策略列;
            // default 列进 SET——update 时自动调用 default 刷新(如 updatedAt)。
            if (!column.isId && column.updatable
                    && (column.hasSetter || column.defaultGetter)) {
                if (!sets.isEmpty()) {
                    sets.append(",");
                }
                sets.append(SqlCodegen.quoteIdentifier(dialect, column.columnName)).append("=?");
            }
        }
        return "UPDATE " + SqlCodegen.quoteIdentifier(dialect, model.tableName()) + " SET " + sets
                + " WHERE " + SqlCodegen.quoteIdentifier(dialect, model.idColumn().columnName) + "=?";
    }

    static String findByIdSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.quotedNames(info.model.columns(), dialect))
                + " FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName()) + " WHERE "
                + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName) + "=?";
    }

    static String findByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.quotedNames(info.model.columns(), dialect))
                + " FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName()) + " WHERE "
                + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName) + " IN (";
    }

    static String findAllSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "SELECT " + SqlCodegen.joinColumns(SqlCodegen.quotedNames(info.model.columns(), dialect))
                + " FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName());
    }

    static String countSql(JForgeProcessor.DaoInfo info) {
        return "SELECT COUNT(*) FROM "
                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
    }

    static String countByIdSql(JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return "SELECT COUNT(*) FROM " + SqlCodegen.quoteIdentifier(dialect, info.model.tableName())
                + " WHERE " + SqlCodegen.quoteIdentifier(dialect, info.model.idColumn().columnName) + "=?";
    }

    /**
     * {@code @Query} 方法的 SQL：命名占位符转 {@code ?}，并拼接静态 {@code @Condition}
     * 追加参数（非 {@code @Nullable}）的条件——动态追加条件运行时拼接，不进入常量。
     */
    static String querySql(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        Query query = method.getAnnotation(Query.class);
        String sql = SqlCodegen.convertPlaceholders(query.value(), new ArrayList<>());
        QueryGenerator.ParsedWhere parsed = QueryGenerator.parseWhere(query.value());
        boolean first = parsed == null || parsed.fragments.isEmpty();
        for (VariableElement parameter : method.getParameters()) {
            Condition where = parameter.getAnnotation(Condition.class);
            if (where == null || QueryGenerator.isNullableParameter(parameter)) {
                continue; // 动态追加（@Nullable）不进 SQL 常量
            }
            String fieldName = where.value().isEmpty()
                    ? parameter.getSimpleName().toString()
                    : where.value();
            String columnName = null;
            for (EntityModel.ColumnModel column : info.model.columns()) {
                if (column.fieldName.equals(fieldName)) {
                    columnName = column.columnName;
                    break;
                }
            }
            if (columnName == null) {
                continue; // 字段不匹配的错误已在 queryMethod 报过
            }
            sql += (first ? " WHERE " : " AND ")
                    + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), columnName)
                    + " " + where.op().sql() + " ?";
            first = false;
        }
        return sql;
    }
}
