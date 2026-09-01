package io.github.erdsgfc.jforge.processor.utils;

import com.palantir.javapoet.*;
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
     * 编译期常量索引的便捷重载：委托 {@link #bindParam(String, String, String, boolean, boolean, String)}
     * （索引经 {@code String.valueOf} 传入）。绑定形态选择见 6 参版本 javadoc——
     * 可空列 {@code setObject}、枚举列 {@code setObject(Types.OTHER)}、转换器列
     * {@code setObject(CONV.toDatabase(...), CONV.sqlType().getVendorTypeNumber())}、
     * 非可空列类型精确 {@code setXxx}。
     *
     * @param typeName       字段类型字符串
     * @param expr           值表达式（实体 getter 调用）
     * @param index          基于 1 的占位符索引（编译期常量）
     * @param nullable       列是否可空（可空实体字段可能在运行时为 {@code null}）
     * @param isEnum         列是否为枚举类型
     * @param converterField 转换器静态字段名（@Convert 列；{@code null} = 无转换器）
     * @return 绑定代码块
     */
    public static CodeBlock bindParam(String typeName, String expr, int index, boolean nullable, boolean isEnum,
            String converterField) {
        return bindParam(typeName, expr, String.valueOf(index), nullable, isEnum, converterField);
    }

    /**
     * 构建实体字段的绑定语句（int 版 {@link #bindParam(String, String, int, boolean, boolean, String)}
     * 的单一实现，索引为编译期常量或运行时表达式）：可空列走 {@code ps.setObject(index, expr)}
     * ——setXxx 对 {@code null} 值自动拆箱抛 NPE（如 {@code setInt} 接收 {@code Integer}
     * null），而 setObject 天然把 null 绑定为 SQL NULL、非 null 值由驱动按参数推断类型；
     * 枚举列走 {@code setObject(index, expr, Types.OTHER)}——pgjdbc 对无显式类型的
     * 枚举对象无法推断 SQL 类型；转换器列走 {@code setObject(index, CONV.toDatabase(expr),
     * CONV.sqlType().getVendorTypeNumber())}——int 类型码发送（3 参 SQLType 版本 pgjdbc
     * 对 JDBCType.OTHER 未实现），默认 OTHER=1111 由驱动/数据库按目标列推断，覆盖
     * {@code sqlType()} 返回 JDBCType 时钉死具体类型；非可空列类型精确 {@code setXxx}。
     *
     * @param typeName       字段类型字符串
     * @param expr           值表达式（实体 getter 调用）
     * @param indexExpr      基于 1 的索引表达式（编译期常量或运行时表达式如 {@code "i++"}）
     * @param nullable       列是否可空（可空实体字段可能在运行时为 {@code null}）
     * @param isEnum         列是否为枚举类型
     * @param converterField 转换器静态字段名（@Convert 列；{@code null} = 无转换器）
     * @return 绑定代码块
     */
    public static CodeBlock bindParam(String typeName, String expr, String indexExpr, boolean nullable, boolean isEnum,
            String converterField) {
        if (converterField != null) {
            // 转换器绑定:setObject(i, v, CONV.sqlType().getVendorTypeNumber())——转 int
            // 类型码发送(3 参 SQLType 版本 pgjdbc 对 JDBCType.OTHER 未实现,抛"方法尚未
            // 实现");默认 OTHER=1111(unknown),由 PG/H2 按目标列推断(jsonb 等不接受
            // varchar 隐式转换的类型也能绑定);用户覆盖 sqlType() 返回 JDBCType 时钉死
            // 具体类型码(驱动自定义 SQLType 需驱动支持其 vendorTypeNumber 语义)。
            return CodeBlock.of("$L.setObject($L, $L.toDatabase($L), $L.sqlType().getVendorTypeNumber());",
                    "ps", indexExpr, converterField, expr, converterField);
        }
        if (isEnum) {
            return CodeBlock.of("$L.setObject($L, $L, $T.OTHER);", "ps", indexExpr, expr,
                    ClassName.get("java.sql", "Types"));
        }
        if (nullable) {
            return CodeBlock.of("$L.setObject($L, $L);", "ps", indexExpr, expr);
        }
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), indexExpr, expr);
    }

    /**
     * 按索引构建列读取语句，并经实体 setter 映射。用于生成的 CRUD——其中 SELECT 列顺序
     * 始终与字段顺序一致。
     *
     * @param typeName       字段类型字符串
     * @param javaType       字段的 JavaPoet 类型（已去除 TYPE_USE 注解）
     * @param javaClassType  用于 {@code getObject(..., type.class)} 的擦除类型
     * @param entityVar      实体变量名
     * @param setterName     builder setter 方法名
     * @param index          基于 1 的列索引
     * @param nullable       列是否可空
     * @param isEnum         列是否为枚举类型（{@code 枚举.valueOf(rs.getString(i))} 读取）
     * @param converterField 转换器静态字段名（{@code CONV.toEntity(rs.getObject(i))} 读取）
     * @return 读取代码块
     */
    public static CodeBlock readColumn(String typeName, TypeName javaType, TypeName javaClassType,
            String entityVar, String setterName, int index,
            boolean nullable, boolean isEnum, String converterField) {
        if (converterField != null) {
            // 转换器列:裸 rs.getObject(i) 取驱动的默认数据库表示(如 PG jsonb → PGobject),
            // 经 toEntity 由转换器转成实体字段类型——适配任意数据库类型(null 透传)。
            return CodeBlock.of("$L.$L($L.toEntity(rs.getObject($L)));",
                    entityVar, setterName, converterField, index);
        }
        if (isEnum) {
            // pgjdbc 不支持 getObject(i, Class) 把 PG enum 转成 Java 枚举——读标签字符串
            // 后 valueOf（可空列对 NULL 返回 null）。
            if (nullable) {
                String var = "v" + setterName;
                return CodeBlock.of("$T $L = rs.getString($L);\n$L.$L($L == null ? null : $T.valueOf($L));",
                        String.class, var, index, entityVar, setterName, var,
                        javaType, var);
            }
            return CodeBlock.of("$L.$L($T.valueOf(rs.getString($L)));",
                    entityVar, setterName, javaType, index);
        }
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            // LocalDate/LocalDateTime/enums: getObject(index, Class) 对 NULL 列天然返回 null,
            // 可空与否形态一致——无需 wasNull 分支。
            return CodeBlock.of("$L.$L($L.getObject($L, $T.class));",
                    entityVar, setterName, "rs", index, javaClassType);
        }
        if (nullable && javaType.isBoxedPrimitive()) {
            // 只有包装基本类型的专用 getter（getInt/getLong 等）需要 wasNull();
            // JDBC 对 String、日期、LOB、数组、URL、byte[] 等引用类型 getter 的 NULL
            // 已直接返回 null，省去局部变量和一次 wasNull() 调用。
            String var = "v" + setterName;
            return CodeBlock.of("$T $L = rs.$L($L);\n$L.$L(rs.wasNull() ? null : $L);",
                    javaType.isBoxedPrimitive() ? javaType.unbox() : javaType,
                    var, getter, index, entityVar, setterName, var);
        }
        return CodeBlock.of("$L.$L($L.$L($L));",
                entityVar, setterName, "rs", getter, index);
    }

    /**
     * 按名称构建列读取语句，并经实体 setter 映射。用于 {@code @Query} 方法——其 SELECT 列顺序
     * 由用户控制。
     *
     * @param typeName       字段类型字符串
     * @param javaType       字段的 JavaPoet 类型（已去除 TYPE_USE 注解）
     * @param javaClassType  用于 {@code getObject(..., type.class)} 的擦除类型
     * @param entityVar      实体变量名
     * @param setterName     builder setter 方法名
     * @param column         列名
     * @param nullable       列是否可空
     * @param isEnum         列是否为枚举类型（{@code 枚举.valueOf(rs.getString(...))} 读取）
     * @param converterField 转换器静态字段名（{@code CONV.toEntity(rs.getObject(...))} 读取）
     * @return 读取代码块
     */
    public static CodeBlock readColumnByName(String typeName, TypeName javaType, TypeName javaClassType,
            String entityVar, String setterName, String column,
            boolean nullable, boolean isEnum, String converterField) {
        if (converterField != null) {
            return CodeBlock.of("$L.$L($L.toEntity(rs.getObject($S)));",
                    entityVar, setterName, converterField, column);
        }
        if (isEnum) {
            if (nullable) {
                String var = "v" + setterName;
                return CodeBlock.of("$T $L = rs.getString($S);\n$L.$L($L == null ? null : $T.valueOf($L));",
                        String.class, var, column, entityVar, setterName, var,
                        javaType, var);
            }
            return CodeBlock.of("$L.$L($T.valueOf(rs.getString($S)));",
                    entityVar, setterName, javaType, column);
        }
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            // getObject 对 NULL 列天然返回 null——可空与否形态一致,无需 wasNull 分支。
            return CodeBlock.of("$L.$L($L.getObject($S, $T.class));",
                    entityVar, setterName, "rs", column, javaClassType);
        }
        if (nullable && javaType.isBoxedPrimitive()) {
            String var = "v" + setterName;
            return CodeBlock.of("$T $L = rs.$L($S);\n$L.$L(rs.wasNull() ? null : $L);",
                    javaType.isBoxedPrimitive() ? javaType.unbox() : javaType,
                    var, getter, column, entityVar, setterName, var);
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
        String quote = null;
        boolean lineComment = false;
        boolean blockComment = false;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (lineComment) {
                out.append(c);
                i++;
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                out.append(c);
                i++;
                if (c == '*' && i < sql.length() && sql.charAt(i) == '/') {
                    out.append('/');
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (quote != null) {
                if (quote.length() > 1 && sql.startsWith(quote, i)) {
                    out.append(quote);
                    i += quote.length();
                    quote = null;
                    continue;
                }
                out.append(c);
                i++;
                if (quote.length() > 1) {
                    continue;
                }
                if (c == '\\' && i < sql.length()) {
                    out.append(sql.charAt(i++));
                } else if (c == quote.charAt(0)) {
                    if (i < sql.length() && sql.charAt(i) == quote.charAt(0)) {
                        out.append(sql.charAt(i++));
                    } else {
                        quote = null;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = String.valueOf(c);
                out.append(c);
                i++;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                lineComment = true;
                out.append("--");
                i += 2;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                blockComment = true;
                out.append("/*");
                i += 2;
                continue;
            }
            if (c == '$') {
                int delimiterEnd = dollarQuoteDelimiterEnd(sql, i);
                if (delimiterEnd >= 0) {
                    quote = sql.substring(i, delimiterEnd);
                    out.append(quote);
                    i = delimiterEnd;
                    continue;
                }
            }
            if (c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
                continue;
            }
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

    /** 返回 PostgreSQL dollar-quote 分隔符结束位置，非分隔符返回 -1。 */
    private static int dollarQuoteDelimiterEnd(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && sql.charAt(i) == '$') {
            return i + 1;
        }
        if (i >= sql.length() || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            return -1;
        }
        i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
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
     * 关闭 SQL 动态构建的事务代码块。与 {@link #endTxBlock(MethodSpec.Builder,
     * ClassName, String, String, String, boolean)} 不同，{@code sqlExpr} 是生成代码中的
     * 表达式（例如 {@code sql.toString()}），因此异常消息和失败日志包含运行时完整 SQL。
     */
    public static void endTxBlockDynamic(MethodSpec.Builder method, ClassName sqlException,
            String operation, String tableName, String sqlExpr, boolean logSql) {
        String prefix = operation + " on table '" + tableName + "' [";
        String suffix = "]: ";
        method.nextControlFlow("catch ($T e)", sqlException);
        if (logSql) {
            method.beginControlFlow("if (log.isWarnEnabled())");
            method.addStatement("log.warn($S, $L, e)", "SQL failed: {}", sqlExpr);
            method.endControlFlow();
        }
        method.addStatement("throw new $T($T.Code.SQL, $S + $L + $S + e.getMessage(), $L, e)",
                ORM_EXCEPTION.getJavaPoetClassName(), ORM_EXCEPTION.getJavaPoetClassName(),
                prefix, sqlExpr, suffix, sqlExpr)
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
     * 按字段名查找宿主实体列，返回其 {@code @Convert} 转换器的静态字段名；该列无转换器
     * 或字段不存在时 {@code null}。供 {@code @Condition}/{@code @UpdateSet} 参数自动复用
     * 列转换器——条件/SET 值须与列存相同的转换后表示才能命中（复用宿主仓库已有的
     * {@code CONVERTER_<实体>_<字段>} 字段，无需新生成）。
     *
     * @param model     实体模型
     * @param fieldName 实体字段名
     * @return 转换器静态字段名；无转换器时 {@code null}
     */
    public static String converterFieldForField(EntityModel model, String fieldName) {
        for (EntityModel.ColumnModel column : model.columns()) {
            if (column.fieldName.equals(fieldName) && column.converter != null) {
                return converterFieldName(model, column);
            }
        }
        return null;
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
        return converterField(column.converter, converterFieldName(model, column));
    }

    /**
     * 构造转换器实例的静态字段：{@code private static final XxxConverter <fieldName>
     * = new XxxConverter();}（转换器须有公开无参构造器）。供 {@code @Bind} 参数转换器
     * 等非实体列场景复用。
     *
     * @param converter 转换器实现类
     * @param fieldName 生成的静态字段名
     * @return 转换器字段规格
     */
    public static FieldSpec converterField(ClassName converter, String fieldName) {
        return FieldSpec.builder(converter, fieldName,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", converter)
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
     * 提取列模型列表的列名的引号变体，并按方言引用符包裹——SELECT / INSERT 列清单用
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
