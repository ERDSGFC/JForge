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
 * 生成 {@code Delete} 声明式删除方法：不写 SQL，按参数自动构造
 * {@code DELETE FROM t WHERE ...}。WHERE 条件与 {@code @Update}/{@code @Select}
 * 同一套（{@link Condition} 参数 / {@link Where} 条件对象，动态/静态形态一致）——
 * 解析与折叠机制继承 {@link AbstractGenerator}。
 */
public final class DeleteGenerator extends AbstractGenerator {

    public DeleteGenerator(javax.annotation.processing.ProcessingEnvironment processingEnv,
                           JForgeConfigHelper configHelper) {
        super(processingEnv, configHelper);
    }

    public void deleteMethod(JForgeProcessor.DaoInfo info, DaoMethod call, TypeSpec.Builder builder,
                             ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        ExecutableElement method = call.method();
        MethodSpec impl = buildDeleteMethod(info, builder, method, call.overloadIndex(), connection,
                preparedStatement, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

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
