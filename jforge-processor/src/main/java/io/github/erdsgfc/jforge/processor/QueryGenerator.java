package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.ReturnGeneratedKeys;
import io.github.erdsgfc.jforge.annotation.Table;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成仓库 impl 类的 {@code @Query} 方法：命名占位符转 {@code ?}、按类型绑定 {@code @Bind} 参数、
 * 按返回类型映射结果（实体 / DTO record / 标量 / 更新计数）。
 *
 * <p>依赖 {@link ProcessingEnvironment}（{@code @Query} 实体结果映射时重新解析实体模型）与
 * {@link JForgeConfigHelper}；其余经 {@link JForgeProcessor.DaoInfo} 参数传入。
 * 实体结果映射的 impl 引用 {@link RepositoryGenerator} 预置的 {@link EmbeddedEntity} 表：
 * 宿主实体直接复用；{@code @Query} 返回其他 {@code @Table} 实体时现场解析并作为
 * {@code private static final} 嵌套类嵌入当前仓库（同实体多个 {@code @Query} 只嵌一份）。</p>
 */
final class QueryGenerator {

    /**
     * 一个将被嵌入当前仓库 impl 的实体 impl 的解析结果：实体模型 + 其在当前仓库内的
     * 嵌套类名（{@code daoPackage.ImplName.EntityImplName}）。
     */
    static final class EmbeddedEntity {
        final ClassName impl;
        final EntityModel model;

        EmbeddedEntity(ClassName impl, EntityModel model) {
            this.impl = impl;
            this.model = model;
        }
    }

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;

    /**
     * @param processingEnv the processing environment (for entity-model re-parse and error reporting)
     * @param configHelper  the shared ORM config helper
     */
    QueryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
    }

    /**
     * 为仓库上每个标注了 {@code @Query} 的方法生成实现方法
     * (SQL 取 {@code <方法名>Sql} 字段)。
     *
     * @param info              仓库信息
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     */
    void queryMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            Map<String, EmbeddedEntity> embedded, ClassName connection, ClassName preparedStatement,
            ClassName resultSet, ClassName sqlException) {
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            builder.addMethod(queryMethod(info, method, query, builder, embedded, connection,
                    preparedStatement, resultSet, sqlException));
        }
    }

    /**
     * 构建一个 {@code @Query} 方法的实现:命名占位符转 {@code ?},按类型绑定每个
     * {@code @Bind} 参数,并按返回类型映射结果(实体、DTO record、标量或影响行数)。
     *
     * @param info              仓库信息
     * @param method            标注了注解的仓库方法
     * @param query             @Query 注解
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return 查询方法规格
     */
    private MethodSpec queryMethod(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String methodName = method.getSimpleName().toString();
        String sqlField = methodName + "Sql";
        List<String> placeholders = new ArrayList<>();
        SqlCodegen.convertPlaceholders(query.value(), placeholders);

        // 映射占位符名 → 方法参数(经 @Bind)。
        Map<String, VariableElement> binds = new HashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            Bind bind = parameter.getAnnotation(Bind.class);
            if (bind != null) {
                binds.put(bind.value(), parameter);
            }
        }

        TypeMirror returnType = method.getReturnType();
        // SELECT queries read a ResultSet; anything else (INSERT/UPDATE/DELETE) returns a count.
        boolean isUpdate = !query.value().trim().toUpperCase().startsWith("SELECT");

        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithGenerics(returnType));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithGenerics(parameter.asType()),
                    parameter.getSimpleName().toString());
        }

        boolean generatedKeys = method.getAnnotation(ReturnGeneratedKeys.class) != null;
        SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, generatedKeys, configHelper.logSql(info.element));

        for (int i = 0; i < placeholders.size(); i++) {
            VariableElement parameter = binds.get(placeholders.get(i));
            if (parameter == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "No @Bind(\"" + placeholders.get(i) + "\") parameter for query placeholder", method);
                continue;
            }
            spec.addCode(SqlCodegen.bindParam(parameter.asType().toString(),
                    parameter.getSimpleName().toString(), i + 1));
            spec.addCode("\n");
        }

        if (generatedKeys) {
            spec.addStatement("ps.executeUpdate()");
            spec.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
            spec.beginControlFlow("if (keys.next())");
            spec.addStatement("$L", generatedKeysWriteback(info, method));
            spec.endControlFlow();
            spec.endControlFlow();
            if (returnType.getKind() == TypeKind.VOID) {
                spec.addStatement("return");
            } else {
                spec.addStatement("return null");
            }
        } else if (isUpdate) {
            spec.addStatement("return ps.executeUpdate()");
        } else {
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            appendResultMapping(spec, info, method, builder, embedded, returnType, query.value());
            spec.endControlFlow();
        }

        SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(), SqlFieldGenerator.querySql(method), configHelper.logSql(info.element));
        return spec.build();
    }

    /**
     * 构建 {@code @ReturnGeneratedKeys} 方法的生成主键回写表达式:找到实体参数,
     * 返回对其 id setter 的赋值。
     *
     * @param info   仓库信息
     * @param method 标注了注解的方法
     * @return 回写表达式;无实体参数时返回 no-op 注释
     */
    private String generatedKeysWriteback(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            TypeMirror type = parameter.asType();
            if (type.getKind() == TypeKind.DECLARED) {
                Element element = ((DeclaredType) type).asElement();
                if (element.getKind() == ElementKind.INTERFACE
                        && element.getAnnotation(Table.class) != null) {
                    EntityModel.ColumnModel idColumn = info.model.idColumn();
                    // 回写接收者:接口有 setter 直接调用;只读 id(无 setter)强转到宿主仓库的
                    // 嵌套实体 impl 调用 private 填充 setter(nestmates 允许宿主类访问)。
                    // getSimpleName() 返回 Name,条件表达式的另一分支是 String——
                    // 必须 toString() 才能让两分支类型一致。
                    String paramName = parameter.getSimpleName().toString();
                    String receiver = idColumn.hasSetter
                            ? paramName
                            : "((" + EntityModel.implNameOf(info.model.entitySimpleName(),
                                    info.model.implSuffix()) + ") " + paramName + ")";
                    return receiver + "." + idColumn.setterName + "(keys.get"
                            + TypeNameUtils.jdbcReturnSuffix(idColumn.typeName) + "(1))";
                }
            }
        }
        return "/* no entity parameter to write back the generated key */";
    }

    /**
     * 按返回类型追加 SELECT {@code @Query} 的结果映射代码:
     * 实体接口(按列名)、DTO record(按组件顺序)或标量。
     *
     * @param spec       接收映射代码的方法构建器
     * @param info       仓库信息
     * @param method     标注了注解的方法
     * @param builder    接收方法的 impl 类构建器
     * @param embedded   已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param returnType 方法的返回类型
     * @param sql        {@code @Query} 的 SQL（实体映射解析 SELECT 列顺序用）
     */
    private void appendResultMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info,
            ExecutableElement method, TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded,
            TypeMirror returnType, String sql) {
        boolean isList = returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().getSimpleName().contentEquals("List");
        TypeMirror elementType = isList
                ? ((DeclaredType) returnType).getTypeArguments().get(0)
                : returnType;

        TypeElement element = elementType.getKind() == TypeKind.DECLARED
                ? (TypeElement) ((DeclaredType) elementType).asElement()
                : null;

        if (element != null && element.getAnnotation(Table.class) != null) {
            // 实体接口:解析 SELECT 列顺序按下标映射(失败回退按列名)。
            appendEntityMapping(spec, info, builder, embedded, elementType, isList, sql, method);
        } else if (element != null && element.getKind() == ElementKind.RECORD) {
            // DTO record:组件顺序按索引映射到 SELECT 列顺序。
            appendRecordMapping(spec, element, isList);
        } else if (elementType.getKind() != TypeKind.VOID) {
            // 单值(String/Long/...)。
            if (isList) {
                spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                        TypeName.get(elementType), ClassName.get("java.util", "ArrayList"));
                spec.beginControlFlow("while (rs.next())");
                spec.addStatement("result.add(rs.$L(1))", TypeNameUtils.jdbcGetter(elementType.toString()));
                spec.endControlFlow();
                spec.addStatement("return result");
            } else {
                spec.beginControlFlow("if (!rs.next())");
                spec.addStatement(elementType.getKind().isPrimitive() ? "return 0" : "return null");
                spec.endControlFlow();
                spec.addStatement("return rs.$L(1)", TypeNameUtils.jdbcGetter(elementType.toString()));
            }
        } else {
            spec.addStatement("return null");
        }
    }

    /**
     * 追加实体接口结果类型的行映射:优先按下标读取——编译期解析 {@code @Query} 的
     * SELECT 列顺序,为每个实体列定位其在 SELECT 中的位置(与 CRUD mapRow 一致,
     * 且 SELECT 缺实体列时编译报错而非静默错位);SQL 无法解析(通配符/函数/复杂
     * 表达式)时回退按列名读取。
     *
     * @param spec       接收映射代码的方法构建器
     * @param info       仓库信息
     * @param builder    接收方法的 impl 类构建器
     * @param embedded   已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param entityType 实体接口类型
     * @param isList     方法是否返回列表
     * @param sql        {@code @Query} 的 SQL（解析 SELECT 列顺序用）
     * @param method     标注了 {@code @Query} 的仓库方法（缺列报错绑定位置）
     */
    private void appendEntityMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info,
            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, TypeMirror entityType,
            boolean isList, String sql, ExecutableElement method) {
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
        // 命中 embedded 表（宿主实体由 RepositoryGenerator 预置）直接复用，避免重复解析；
        // 非宿主实体（@Query 返回其他 @Table 实体）现场解析并作为嵌套类嵌入当前仓库，
        // 同实体多个 @Query 靠表去重只嵌一份。
        EmbeddedEntity ctx = embedded.get(entityElement.getQualifiedName().toString());
        if (ctx == null) {
            EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                    Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
            if (model == null) {
                return;
            }
            // 嵌套类名必须用该实体自身的 implSuffix() 计算（跨包可配置不同后缀），
            // 不能复用宿主仓库的值。
            ClassName impl = ClassName.get(info.daoPackage, info.implName,
                    EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()));
            ctx = new EmbeddedEntity(impl, model);
            embedded.put(entityElement.getQualifiedName().toString(), ctx);
            builder.addType(EntityGenerator.buildImpl(model));
        }
        ClassName impl = ctx.impl;
        EntityModel model = ctx.model;
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    TypeName.get(entityType), ClassName.get("java.util", "ArrayList"));
            spec.beginControlFlow("while (rs.next())");
            spec.addStatement("$T e = new $T()", impl, impl);
            appendEntityColumnReads(spec, model, sql, method);
            spec.addStatement("result.add(e)");
            spec.endControlFlow();
            spec.addStatement("return result");
        } else {
            spec.beginControlFlow("if (!rs.next())");
            spec.addStatement("return null");
            spec.endControlFlow();
            spec.addStatement("$T e = new $T()", impl, impl);
            appendEntityColumnReads(spec, model, sql, method);
            spec.addStatement("return e");
        }
    }

    /**
     * 追加实体所有列的读取代码:优先按解析出的 SELECT 列顺序按下标读取
     * (与 CRUD mapRow 一致;SELECT 缺实体列时编译报错),解析失败回退按列名。
     */
    private void appendEntityColumnReads(MethodSpec.Builder spec, EntityModel model, String sql,
            ExecutableElement method) {
        List<String> selectColumns = parseSelectColumns(sql);
        for (EntityModel.ColumnModel column : model.columns()) {
            if (selectColumns != null) {
                int pos = indexOfIgnoreCase(selectColumns, column.columnName);
                if (pos < 0) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@Query SELECT is missing column '" + column.columnName + "' required by entity "
                                    + model.entityQualifiedName() + ": " + sql, method);
                    return;
                }
                spec.addCode(SqlCodegen.readColumn(column.typeName, "e", column.setterName, pos + 1));
            } else {
                spec.addCode(SqlCodegen.readColumnByName(column.typeName, "e", column.setterName, column.columnName));
            }
            spec.addCode("\n");
        }
    }

    /**
     * 追加 DTO record 结果类型的行映射:record 组件顺序按索引映射到 SELECT 列顺序。
     *
     * @param spec   接收映射代码的方法构建器
     * @param record DTO record 元素
     * @param isList 方法是否返回列表
     */
    private void appendRecordMapping(MethodSpec.Builder spec, TypeElement record, boolean isList) {
        ClassName recordClass = ClassName.get(record);
        List<? extends Element> components = record.getRecordComponents();
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    recordClass, ClassName.get("java.util", "ArrayList"));
            spec.beginControlFlow("while (rs.next())");
            spec.addStatement("$T dto = new $T($L)", recordClass, recordClass,
                    recordArgs(components));
            spec.addStatement("result.add(dto)");
            spec.endControlFlow();
            spec.addStatement("return result");
        } else {
            spec.beginControlFlow("if (!rs.next())");
            spec.addStatement("return null");
            spec.endControlFlow();
            spec.addStatement("return new $T($L)", recordClass, recordArgs(components));
        }
    }

    /**
     * 从当前行构建 record 的构造参数列表。
     *
     * @param components record 组件
     * @return 逗号连接的 {@code rs.getXxx(i)} 表达式
     */
    private String recordArgs(List<? extends Element> components) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String typeName = components.get(i).asType().toString();
            sb.append("rs.").append(TypeNameUtils.jdbcGetter(typeName)).append("(").append(i + 1).append(")");
        }
        return sb.toString();
    }

    // ---- SELECT 列顺序解析(实体映射按下标读取) -------------------------------

    /**
     * 解析 SELECT 查询的顶层列名列表,用于把 {@code @Query} 实体映射从按名称改为
     * 按下标读取(与 CRUD mapRow 一致,且能在编译期校验 SELECT 缺列)。
     *
     * <p>支持:逗号分隔的列名、{@code AS} 别名、表前缀(如 {@code u.name})、
     * 引号列名;遇到通配符({@code *})、函数、括号等无法可靠解析的表达式返回
     * {@code null}——调用方回退到按列名映射(安全降级,不误报)。</p>
     *
     * @param sql {@code @Query} 的 SQL
     * @return SELECT 列名列表(按书写顺序),解析失败返回 {@code null}
     */
    private static List<String> parseSelectColumns(String sql) {
        int select = indexOfTopLevelKeyword(sql, "SELECT", 0);
        if (select < 0) {
            return null;
        }
        int from = indexOfTopLevelKeyword(sql, "FROM", select + 6);
        if (from < 0) {
            return null;
        }
        String list = sql.substring(select + 6, from).replaceFirst("(?i)\\s+DISTINCT\\s+", " ");
        List<String> columns = new ArrayList<>();
        for (String item : splitTopLevel(list)) {
            String name = selectColumnName(item);
            if (name == null) {
                return null; // 通配符或无法解析 → 回退按名称
            }
            columns.add(name);
        }
        return columns.isEmpty() ? null : columns;
    }

    /** 顶层(括号深度 0)逗号分割;函数参数内的逗号不分割。 */
    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * 提取单个 SELECT 项的列名:去 AS 别名与表前缀({@code u.name AS n} → {@code name};
     * 按下标读取时别名不影响位置)、去引号;含通配符或函数/表达式返回 {@code null}。
     */
    private static String selectColumnName(String item) {
        String s = item.trim();
        if (s.isEmpty() || s.indexOf('*') >= 0 || s.indexOf('(') >= 0) {
            return null;
        }
        int as = indexOfTopLevelKeyword(s, "AS", 0);
        if (as >= 0) {
            s = s.substring(0, as);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        s = s.trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '`')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    /** 从 {@code from} 起查找下一个顶层(括号深度 0、非引号内)关键字,大小写不敏感。 */
    private static int indexOfTopLevelKeyword(String s, String keyword, int from) {
        String lower = s.toLowerCase();
        String kw = keyword.toLowerCase();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '`' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth == 0 && lower.startsWith(kw, i)
                    && (i == 0 || !isIdentifierChar(s.charAt(i - 1)))
                    && (i + kw.length() >= s.length() || !isIdentifierChar(s.charAt(i + kw.length())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int indexOfIgnoreCase(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
