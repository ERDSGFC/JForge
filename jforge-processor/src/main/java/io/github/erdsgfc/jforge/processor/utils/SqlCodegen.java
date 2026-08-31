package io.github.erdsgfc.jforge.processor.utils;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.DialectSupport;
import io.github.erdsgfc.jforge.processor.EntityModel;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;

import static io.github.erdsgfc.jforge.processor.ClassEnum.JDBC_STATEMENT;
import static io.github.erdsgfc.jforge.processor.ClassEnum.ORM_EXCEPTION;

/**
     * 构建各生成仓库实现共享的 JDBC 代码片段（参数绑定、行映射）。所有类型决策都在编译期
     * 完成——生成的代码直接调用类型精确的 setter/getter。
     */
public final class SqlCodegen {

    private SqlCodegen() {
    }

    /**
     * 构建带常量索引的类型精确参数绑定语句：{@code ps.setXxx(index, expr);}。
     *
     * @param typeName 参数的声明类型（如 {@code "int"}、{@code "java.lang.String"}）
     * @param expr     值表达式（如 {@code "id"}、{@code "entity.age()"}）
     * @param index    基于 1 的占位符索引（编译期常量）
     * @return 绑定代码块
     */
    public static CodeBlock bindParam(String typeName, String expr, int index) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), index, expr);
    }

    /**
     * 构建实体字段的绑定语句：可空列走 {@code ps.setObject(index, expr)}——setXxx
     * 对 {@code null} 值自动拆箱抛 NPE（如 {@code setInt} 接收 {@code Integer} null），
     * 而 setObject 天然把 null 绑定为 SQL NULL、非 null 值由驱动按参数推断类型；
     * 枚举列走 {@code setObject(index, expr, Types.OTHER)}——pgjdbc 对无显式类型的
     * 枚举对象无法推断 SQL 类型；转换器列走 {@code setObject(i, CONV.toDatabase(expr))}；
     * 非可空列与 {@link #bindParam(String, String, int)} 相同（类型精确 setXxx）。
     *
     * @param typeName      字段类型字符串
     * @param expr          值表达式（实体 getter 调用）
     * @param index         基于 1 的占位符索引（编译期常量）
     * @param nullable      列是否可空（可空实体字段可能在运行时为 {@code null}）
     * @param isEnum        列是否为枚举类型
     * @param converterField 转换器静态字段名（@Convert 列；{@code null} = 无转换器）
     * @return 绑定代码块
     */
    public static CodeBlock bindParam(String typeName, String expr, int index, boolean nullable, boolean isEnum,
            String converterField) {
        if (converterField != null) {
            return CodeBlock.of("$L.setObject($L, $L.toDatabase($L));", "ps", index,
                    converterField, expr);
        }
        if (isEnum) {
            return CodeBlock.of("$L.setObject($L, $L, $T.OTHER);", "ps", index, expr,
                    ClassName.get("java.sql", "Types"));
        }
        if (nullable) {
            return CodeBlock.of("$L.setObject($L, $L);", "ps", index, expr);
        }
        return bindParam(typeName, expr, index);
    }

    /**
     * 构建带运行时索引表达式的类型精确参数绑定语句，用于遍历变长参数列表的循环内部。
     *
     * @param typeName  参数的声明类型
     * @param expr      值表达式
     * @param indexExpr 运行时索引表达式（如循环变量 {@code "i"}）
     * @return 绑定代码块
     */
    public static CodeBlock bindParam(String typeName, String expr, String indexExpr) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), indexExpr, expr);
    }

    /**
     * 按索引构建列读取语句，并经实体 setter 映射。用于生成的 CRUD——其中 SELECT 列顺序
     * 始终与字段顺序一致。
     *
     * @param typeName         字段类型字符串
     * @param entityVar        实体变量名
     * @param setterName       builder setter 方法名
     * @param index            基于 1 的列索引
     * @param nullable         列是否可空
     * @param isEnum           列是否为枚举类型（{@code 枚举.valueOf(rs.getString(i))} 读取）
     * @param converterField   转换器静态字段名（{@code CONV.toEntity(rs.getObject(i))} 读取）
     * @param converterDbType  转换器的数据库侧类型 Y（{@code rs.getObject(i, Y.class)}）
     * @return 读取代码块
     */
    public static CodeBlock readColumn(String typeName, String entityVar, String setterName, int index,
            boolean nullable, boolean isEnum, String converterField, ClassName converterDbType) {
        if (converterField != null) {
            // 转换器列:按数据库侧类型精确读取后经 toEntity 还原(null 透传给转换器,
            // 无需 wasNull 分支)。
            return CodeBlock.of("$L.$L($L.toEntity(rs.getObject($L, $T.class)));",
                    entityVar, setterName, converterField, index, converterDbType);
        }
        if (isEnum) {
            // pgjdbc 不支持 getObject(i, Class) 把 PG enum 转成 Java 枚举——读标签字符串
            // 后 valueOf（可空列对 NULL 返回 null）。
            if (nullable) {
                return CodeBlock.of("$T $L = rs.getString($L);\n$L.$L($L == null ? null : $T.valueOf($L));",
                        String.class, "vs", index, entityVar, setterName, "vs",
                        TypeNameUtils.toTypeName(typeName), "vs");
            }
            return CodeBlock.of("$L.$L($T.valueOf(rs.getString($L)));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName), index);
        }
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            // LocalDate/LocalDateTime/enums: getObject(index, Class) 对 NULL 列天然返回 null,
            // 可空与否形态一致——无需 wasNull 分支。
            return CodeBlock.of("$L.$L(($T) $L.getObject($L, $T.class));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName),
                    "rs", index, TypeNameUtils.toTypeName(typeName));
        }
        if (nullable) {
            // 可空列:基本类型局部变量承接读取值(零装箱),经 rs.wasNull() 三元回退 null——
            // 否则 NULL 列对 getInt/getString 等会读得 0/空串而非 null。
            String var = "v" + setterName;
            String varType = TypeNameUtils.jdbcVarType(typeName);
            return CodeBlock.of("$T $L = rs.$L($L);\n$L.$L(rs.wasNull() ? null : $L);",
                    TypeNameUtils.toTypeName(varType), var, getter, index, entityVar, setterName, var);
        }
        return CodeBlock.of("$L.$L($L.$L($L));",
                entityVar, setterName, "rs", getter, index);
    }

    /**
     * 按名称构建列读取语句，并经实体 setter 映射。用于 {@code @Query} 方法——其 SELECT 列顺序
     * 由用户控制。
     *
     * @param typeName         字段类型字符串
     * @param entityVar        实体变量名
     * @param setterName       builder setter 方法名
     * @param column           列名
     * @param nullable         列是否可空
     * @param isEnum           列是否为枚举类型（{@code 枚举.valueOf(rs.getString(...))} 读取）
     * @param converterField   转换器静态字段名（{@code CONV.toEntity(rs.getObject(...))} 读取）
     * @param converterDbType  转换器的数据库侧类型 Y（{@code rs.getObject(..., Y.class)}）
     * @return 读取代码块
     */
    public static CodeBlock readColumnByName(String typeName, String entityVar, String setterName, String column,
            boolean nullable, boolean isEnum, String converterField, ClassName converterDbType) {
        if (converterField != null) {
            return CodeBlock.of("$L.$L($L.toEntity(rs.getObject($S, $T.class)));",
                    entityVar, setterName, converterField, column, converterDbType);
        }
        if (isEnum) {
            if (nullable) {
                return CodeBlock.of("$T $L = rs.getString($S);\n$L.$L($L == null ? null : $T.valueOf($L));",
                        String.class, "vs", column, entityVar, setterName, "vs",
                        TypeNameUtils.toTypeName(typeName), "vs");
            }
            return CodeBlock.of("$L.$L($T.valueOf(rs.getString($S)));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName), column);
        }
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            // getObject 对 NULL 列天然返回 null——可空与否形态一致,无需 wasNull 分支。
            return CodeBlock.of("$L.$L(($T) $L.getObject($S, $T.class));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName),
                    "rs", column, TypeNameUtils.toTypeName(typeName));
        }
        if (nullable) {
            String var = "v" + setterName;
            String varType = TypeNameUtils.jdbcVarType(typeName);
            return CodeBlock.of("$T $L = rs.$L($S);\n$L.$L(rs.wasNull() ? null : $L);",
                    TypeNameUtils.toTypeName(varType), var, getter, column, entityVar, setterName, var);
        }
        return CodeBlock.of("$L.$L($L.$L($S));",
                entityVar, setterName, "rs", getter, column);
    }

    /**
     * 拼接列名，用于 SELECT / INSERT 列清单。
     *
     * @param names 列名
     * @return 以逗号拼接的字符串
     */
    public static String joinColumns(List<String> names) {
        return String.join(",", names);
    }

    /**
     * 用方言引用符包裹自动生成的标识符（表名/列名），实现数据库端精确匹配：
     * PG/SQLite 双引号、MySQL 反引号。包裹规则（保守，避免破坏既有行为）——
     * <ul>
     *   <li>名字已含方言引用符（用户自行引用的显式名，如 {@code @Table(name="\"Users\"")}）不包；</li>
     *   <li>名字含大写字母不包——未引用的 DDL（{@code CREATE TABLE Users}）在 PG 折叠为小写存储，
     *       包裹会让生成 SQL 按大小写精确查找而失败；</li>
     *   <li>其余（自动推导的 camelToSnake 小写名、显式小写名）包裹——修复保留字列
     *       （{@code order}/{@code key}）与 Linux MySQL 表名大小写敏感问题。</li>
     * </ul>
     * 只用于自动生成的 SQL 文本；用户 SQL（{@code @Query} 方法体、{@code rawSql} 片段）
     * 原样透传、永不包裹。ResultSet 按标签读取（{@code rs.getString(name)}）也不包裹。
     *
     * @param dialect 生效的方言支持（引用符来源）
     * @param name    标识符（原始名，如 {@code "user_name"}）
     * @return 包裹后的标识符（如 {@code "\"user_name\""}），不满足包裹规则时原样返回
     */
    public static String quoteIdentifier(DialectSupport dialect, String name) {
        String quote = dialect.quote();
        if (quote.isEmpty() || name.indexOf(quote.charAt(0)) >= 0) {
            return name;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) {
                return name;
            }
        }
        return quote + name + quote;
    }

    /**
     * 构建占位符列表，如 3 → {@code "?,?,?"}。
     *
     * @param count 占位符数量
     * @return 占位符字符串
     */
    public static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /**
     * 把 SQL 字符串中的 {@code :name} 占位符转换为 {@code ?} 占位符。
     *
     * @param sql              带命名占位符的 SQL（如 {@code "WHERE age > :age"}）
     * @param placeholderOrder 按出现顺序接收占位符名称
     * @return 含 {@code ?} 占位符的 SQL
     */
    public static String convertPlaceholders(String sql, List<String> placeholderOrder) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == ':' && i + 1 < sql.length() && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int start = ++i;
                while (i < sql.length() && Character.isJavaIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                placeholderOrder.add(sql.substring(start, i));
                out.append('?');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 在生成的方法中开启感知事务的代码块：获取连接，并把 {@code PreparedStatement} 作为
     * try-with-resources 资源打开，确保始终被关闭——
     * {@code Connection conn = getConnection(); try (PreparedStatement ps = conn.prepareStatement(...)) \{}。
     *
     * @param method            方法构建器
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlExpr           传给 prepareStatement 的 SQL 表达式（SQL 字段名，或动态构建的
     *                          IN 查询用 {@code sql.toString()}）
     * @param generatedKeys     是否使用 {@code RETURN_GENERATED_KEYS}
     */
    public static MethodSpec.Builder beginTxBlock(MethodSpec.Builder method, ClassName connection,
            ClassName preparedStatement, String sqlExpr, boolean generatedKeys, boolean logSql) {
        method.addStatement("$T conn = getConnection()", connection);
        if (logSql) {
            method.beginControlFlow("if (log.isDebugEnabled())");
            method.addStatement("log.debug($S, $L)", "Executing SQL: {}", sqlExpr);
            method.endControlFlow();
        }
        if (generatedKeys) {
            return method.beginControlFlow("try ($T ps = conn.prepareStatement($L, $T.RETURN_GENERATED_KEYS))",
                    preparedStatement, sqlExpr, JDBC_STATEMENT.getJavaPoetClassName());
        }
        return method.beginControlFlow("try ($T ps = conn.prepareStatement($L))", preparedStatement, sqlExpr);
    }

    /**
     * 以 catch + finally（releaseConnection）关闭感知事务的 try 块。catch 抛出
     * {@code JForgeException}，其消息内嵌操作名、表名与 SQL，以及底层 {@code SQLException}
     * 的消息——失败信息自描述，无需深挖异常链。
     *
     * @param method       方法构建器
     * @param sqlException SQLException 类
     * @param operation    操作名（如 {@code "save"}、{@code "findById"}）
     * @param tableName    操作目标表
     * @param sql          失败的 SQL 语句（动态 IN 查询时为其固定前缀）
     */
    public static void endTxBlock(MethodSpec.Builder method, ClassName sqlException,
            String operation, String tableName, String sql, boolean logSql) {
        String message = operation + " on table '" + tableName + "' [" + sql + "]: ";
        method.nextControlFlow("catch ($T e)", sqlException);
        if (logSql) {
            method.beginControlFlow("if (log.isWarnEnabled())");
            method.addStatement("log.warn($S, $S, e)", "SQL failed: {}", sql);
            method.endControlFlow();
        }
        method.addStatement("throw new $T($T.Code.SQL, $S + e.getMessage(), $S, e)",
                ORM_EXCEPTION.getJavaPoetClassName(), ORM_EXCEPTION.getJavaPoetClassName(), message, sql)
                .nextControlFlow("finally")
                .addStatement("releaseConnection(conn)")
                .endControlFlow();
    }

    /**
     * 转换器列的静态字段名：{@code CONVERTER_<实体简单名大写>_<字段名大写>}——字段按列
     * 唯一（同实体两列不可能同字段名），宿主实体与 {@code @Query} 嵌入实体各自生成
     * 字段时因实体名不同不会冲突。
     *
     * @param model  转换器列所属的实体模型
     * @param column 标了 {@code @Convert} 的列
     * @return 生成的 impl 中该转换器的静态字段名
     */
    public static String converterFieldName(EntityModel model, EntityModel.ColumnModel column) {
        return "CONVERTER_" + model.entitySimpleName().toUpperCase() + "_" + column.fieldName.toUpperCase();
    }

    /**
     * 构造转换器实例的静态字段：{@code private static final XxxConverter
     * CONVERTER_X_Y = new XxxConverter();}（转换器须有公开无参构造器）。
     *
     * @param model  转换器列所属的实体模型
     * @param column 标了 {@code @Convert} 的列
     * @return 转换器字段规格
     */
    public static FieldSpec converterField(EntityModel model, EntityModel.ColumnModel column) {
        return FieldSpec.builder(column.converter, converterFieldName(model, column),
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", column.converter)
                .build();
    }

    /**
     * 计算实体的 INSERT 列：数据库生成的主键（由数据库在插入时分配）与以下列被排除——
     * 纯只读列（无 setter 且无 default 默认值，由数据库维护）、{@code UPDATE_ONLY}/
     * {@code NONE} 策略列。default getter 列会保留——save 时绑定其默认值。
     *
     * @param model 已解析的实体模型
     * @return INSERT 中需要绑定的列
     */
    public static List<EntityModel.ColumnModel> insertColumns(EntityModel model) {
        List<EntityModel.ColumnModel> columns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!(column.isId && model.idGenerated())
                    && column.insertable
                    && (column.hasSetter || column.defaultGetter)) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * 提取列模型列表的列名。
     *
     * @param columns 列模型
     * @return 按顺序排列的列名字符串
     */
    public static List<String> namesOf(List<EntityModel.ColumnModel> columns) {
        List<String> names = new ArrayList<>();
        for (EntityModel.ColumnModel column : columns) {
            names.add(column.columnName);
        }
        return names;
    }

    /**
     * 提取列模型列表的列名，并按方言引用符包裹——SELECT / INSERT 列清单用
     * （{@link #namesOf} 的引号变体）。
     *
     * @param columns 列模型
     * @param dialect 生效的方言支持（引用符来源）
     * @return 按顺序排列的包裹后列名字符串
     */
    public static List<String> quotedNames(List<EntityModel.ColumnModel> columns, DialectSupport dialect) {
        List<String> names = new ArrayList<>();
        for (EntityModel.ColumnModel column : columns) {
            names.add(quoteIdentifier(dialect, column.columnName));
        }
        return names;
    }
}
