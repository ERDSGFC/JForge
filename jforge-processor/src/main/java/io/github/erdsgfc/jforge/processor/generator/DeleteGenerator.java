package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.core.AbstractGenerator;
import io.github.erdsgfc.jforge.processor.generator.core.DaoMethod;
import io.github.erdsgfc.jforge.processor.generator.core.WhereCondition;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import java.util.Map;

/**
 * 生成 {@code @Delete} 声明式删除方法的实现：不写 SQL，按方法参数自动构造
 * {@code DELETE FROM t WHERE ...}。
 *
 * <p><b>参数语义</b>：{@code @Where} 参数是条件对象（递归展开其字段为条件，支持
 * 括号分组 / {@code @And}/{@code @Or} 连接 / {@code Optional} 的 IS NULL）；其余参数为
 * 单条件（字段名缺省按参数名，{@code @Condition} 可指定列与操作符；数组/集合参数生成
 * {@code IN}/{@code NOT IN}）。参数可空性按 JSpecify 判定——可空参数运行时为 {@code null}
 * 时整段条件跳过。语义与 {@code @Select}/{@code @Update} 完全一致。</p>
 *
 * <p><b>生成形态</b>（两态自动选择，见 {@link AbstractGenerator}）：</p>
 * <ul>
 *   <li><b>全静态</b>——WHERE 全部条件编译期确定时折叠为 SQL 常量字段 + 静态索引绑定，
 *       运行时零拼接（与手写 JDBC 等价）；</li>
 *   <li><b>动态</b>——含可空参数或条件对象组时生成运行时 {@code StringBuilder} 拼接：
 *       头部连续的全静态条件仍折叠为常量前缀，{@code where} 前缀变量按首个实际生效的
 *       条件给出 {@code WHERE}/{@code AND}；若全部条件被跳过则 WHERE 缺失，此时以早退
 *       （返回 {@code 0}/{@code false}）阻断"无 WHERE 的全表删除"。</li>
 * </ul>
 *
 * <p>返回类型为 {@code boolean} 时早退返回 {@code false}，否则返回 {@code 0}——
 * 与生成方法的返回类型一致。</p>
 */
public final class DeleteGenerator extends AbstractGenerator {

    /**
     * @param processingEnv 处理环境（messager 报错、类型工具）
     * @param configHelper  共享的 ORM 配置 helper（logSql / 命名策略）
     */
    public DeleteGenerator(javax.annotation.processing.ProcessingEnvironment processingEnv,
                           JForgeConfigHelper configHelper) {
        super(processingEnv, configHelper);
    }

    /**
     * 为仓库上单个 {@code @Delete} 方法生成实现，并挂到 impl 类构建器。
     * 生成失败（参数校验报错）时跳过而不添加方法，避免 {@code addMethod(null)}
     * 让 javapoet 抛 NPE 掩盖真实编译错误。
     *
     * @param info              仓库信息
     * @param call              方法（含同名序号）
     * @param builder           接收方法的 impl 类构建器
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     */
    public void deleteMethod(JForgeProcessor.DaoInfo info, DaoMethod call, TypeSpec.Builder builder,
                             ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        ExecutableElement method = call.method();
        MethodSpec impl = buildDeleteMethod(info, builder, method, call.overloadIndex(), connection,
                preparedStatement, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

    /**
     * 构建一个 {@code @Delete} 方法的完整实现（SQL 常量字段/拼接代码 + 方法体）。
     *
     * @param info              仓库信息（实体模型、表名、方言）
     * @param builder           接收方法的 impl 类构建器（SQL 常量字段挂到生成类）
     * @param method            标注了 {@code @Delete} 的仓库方法
     * @param overloadIndex     同名方法序号（SQL 字段名唯一性）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     * @return 方法规格；参数校验失败已报错返回 {@code null}
     */
    private MethodSpec buildDeleteMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            ExecutableElement method, int overloadIndex, ClassName connection, ClassName preparedStatement,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();

        // WHERE 条件：@Condition 参数 + @Where 条件对象（与 @Select/@Update 同一套）。
        WhereParts parts = resolveWhereParts(info, method, Map.of(), false);
        if (parts == null) {
            return null;
        }

        String baseSql = "DELETE FROM "
                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
        MethodSpec.Builder spec = methodShell(methodName, method);
        boolean logSql = configHelper.logSql(info.element);
        emitTopLevelRequireNonNull(spec, method);

        // 全静态：WHERE 无动态参数，且条件对象（若存在）参数非空、字段全静态 →
        // SQL 常量 + 静态绑定（条件对象组同样折叠，不做运行时 StringBuilder 拼接）。
        if (allStatic(parts)) {
            StringBuilder sql = new StringBuilder(baseSql);
            appendStaticWhere(sql, parts);
            addStaticSqlField(builder, sql.toString(), methodName, overloadIndex);
            String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            appendStaticWhereBinds(spec, parts, 1);
            spec.addStatement("return ps.executeUpdate()");
            SqlCodegen.endTxBlockField(spec, sqlException, methodName, info.model.tableName(),
                    sqlField, logSql);
            return spec.build();
        }

        // 动态形态：静态前缀折叠 + where 前缀变量 + 双阶段 if 展开绑定。
        spec.addStatement("$T conn = getConnection()", connection);
        // 前缀已含 WHERE 时剩余动态段从 AND 续拼，防无条件 DELETE 的守卫也无需再生成。
        int staticPrefix = emitPrefixedStringBuilder(spec, builder, parts, baseSql, methodName, overloadIndex);
        boolean hasPrefix = staticPrefix > 0;
        spec.addStatement("$T where = $S", ClassName.get(String.class), hasPrefix ? " AND " : " WHERE ");
        for (int i = staticPrefix; i < parts.conditions.size(); i++) {
            WhereCondition.appendSql(spec, parts.conditions.get(i));
        }
        criteriaGenerator.emitGroupAppend(spec, parts.criteriaUnits, "where", " AND ");
        // 守卫仅在"WHERE 可能整体缺失"时生成：前缀已含 WHERE 时恒安全；
        // 否则 dynamic 条件可能全跳过/条件对象组可能全空回退。
        if (!hasPrefix
                && (parts.conditions.stream().anyMatch(WhereCondition::dynamic)
                        || !parts.criteriaUnits.isEmpty())) {
            spec.beginControlFlow("if (where.equals($S))", " WHERE ");
            spec.addStatement(method.getReturnType().getKind() == TypeKind.BOOLEAN
                    ? "return false" : "return 0");
            spec.endControlFlow();
        }
        // 固化 SQL 字符串:DEBUG 日志、prepareStatement 与 catch 复用同一份,只 toString 一次。
        SqlCodegen.beginTxBlockVar(spec, preparedStatement, logSql);
        spec.addStatement("int i = 1");
        for (WhereCondition condition : parts.conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, parts.criteriaUnits, "i++");
        spec.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlockVar(spec, sqlException, methodName, info.model.tableName(), logSql);
        return spec.build();
    }

}
