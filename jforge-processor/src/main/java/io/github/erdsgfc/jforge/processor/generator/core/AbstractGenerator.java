package io.github.erdsgfc.jforge.processor.generator.core;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.SqlFieldGenerator;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 语句生成器（{@code @Query}/{@code @Select}/{@code @Update}/{@code @Delete}）的公共基类——
 * 收编四个生成器完全一致的机械部分：
 *
 * <ul>
 *   <li><b>共享状态</b>：处理环境（报错）、配置 helper（logSql/命名策略）、条件对象
 *       生成器（{@code @Where} 参数展开）——各生成器原各自持有同样三件套；</li>
 *   <li><b>方法外壳</b>：{@code @Override public 返回值 方法名(参数...)} 的签名装配
 *       （含 JSpecify 空性标注的参数类型）；</li>
 *   <li><b>WHERE 语义</b>（{@code @Select}/{@code @Update}/{@code @Delete} 三者逐字一致，
 *       见 {@link #resolveWhereParts}）：
 *       <ul>
 *         <li>参数分类——{@code @Where} 参数为条件对象（递归展开），其余经
 *             {@link WhereCondition#resolveHost} 解析为单条件；</li>
 *         <li>方法顶部非空契约参数快速失败；</li>
 *         <li>全静态判定与折叠——SQL 常量字段 + 静态索引绑定；</li>
 *         <li>动态形态的静态前缀折叠——头部连续全静态条件并入常量字段。</li>
 *       </ul></li>
 * </ul>
 *
 * <p>{@code @Query} 的片段式解析（{@code :name}/{@code {:name}} 逐 token 展开）语义
 * 与声明式三件套不同，仅复用本基类的共享状态与外壳，不参与 WHERE 机制。</p>
 */
public abstract class AbstractGenerator {

    /** 处理环境（编译期报错、类型工具）。 */
    protected final ProcessingEnvironment processingEnv;

    /** 共享的 ORM 配置 helper（logSql / 命名策略）。 */
    protected final JForgeConfigHelper configHelper;

    /** 条件对象（{@code @Where} 参数）的展开生成器——各生成器原各自 new 一个，语义相同。 */
    protected final CriteriaGenerator criteriaGenerator;

    /**
     * @param processingEnv 处理环境（messager 报错）
     * @param configHelper  共享的 ORM 配置 helper
     */
    protected AbstractGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR, processingEnv.getTypeUtils());
    }

    /**
     * 报编译期错误（绑定到具体元素）。
     *
     * @param element 出错位置（方法/参数/字段）
     * @param message 错误消息
     */
    protected void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    /**
     * 判定类型是否为 {@code Iterable}（含 {@code List}/{@code Set} 等集合）——
     * 用于把集合参数识别为 {@code IN}/{@code NOT IN} 条件。
     *
     * <p>用 {@code isAssignable} 而非 {@code isSubtype}：前者更贴近 Java 编译器的
     * 赋值兼容性判定。两侧都取 {@code erasure}——泛型信息对"是否为集合"的判定无意义，
     * 且擦除后比较可避免 {@code List<String>} 与 {@code Iterable<?>} 的类型实参干扰。</p>
     *
     * <p>静态版供静态工具（如 {@link WhereCondition}）复用；实例方法
     * {@link #isIterable(TypeMirror)} 委托本方法，实现只有一份。</p>
     *
     * @param type 待判定类型
     * @param env  处理环境（取类型工具）
     * @return 是 {@code Iterable} 子类型时 {@code true}
     */
    public static boolean isIterable(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeMirror iterable = env.getElementUtils().getTypeElement("java.lang.Iterable").asType();
        return env.getTypeUtils().isAssignable(
                env.getTypeUtils().erasure(type),
                env.getTypeUtils().erasure(iterable));
    }

    /**
     * 判定类型是否为 {@code Iterable}——用本实例的处理环境，委托
     * {@link #isIterable(TypeMirror, ProcessingEnvironment)}。
     *
     * @param type 待判定类型
     * @return 是 {@code Iterable} 子类型时 {@code true}
     */
    protected boolean isIterable(TypeMirror type) {
        return isIterable(type, processingEnv);
    }

    /**
     * 装配方法外壳：{@code @Override public 返回类型 方法名(带空性标注的参数...)}。
     * 四个生成器原各自内联同一段装配代码。
     *
     * @param methodName 生成的方法名（与仓库接口方法同名）
     * @param method    声明式仓库方法（返回类型/参数来源）
     * @return 已装配签名的方法构建器（方法体由调用方填充）
     */
    protected MethodSpec.Builder methodShell(String methodName, ExecutableElement method) {
        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithNullability(
                        method.getReturnType(), method, processingEnv.getTypeUtils()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithNullability(
                            parameter.asType(), parameter, processingEnv.getTypeUtils()),
                    parameter.getSimpleName().toString());
        }
        return spec;
    }

    /**
     * 一个声明式方法的 WHERE 部分解析结果：直接条件序列 + 条件对象展开单元 +
     * "任一 {@code @Where} 参数可空"标志（可空 → 整体运行时可能跳过，静态折叠不可用）。
     */
    public static final class WhereParts {
        /** 直接条件（非 {@code @Where} 参数经 {@link WhereCondition#resolveHost} 解析）。 */
        public final List<WhereCondition> conditions = new ArrayList<>();
        /** 条件对象（{@code @Where} 参数）的展开单元。 */
        public final List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        /** 任一 {@code @Where} 参数可空 → 整体运行时可能跳过,静态折叠不可用。 */
        public boolean criteriaNullable;
    }

    /**
     * 解析声明式方法的参数为 WHERE 部分（{@code @Select}/{@code @Update}/{@code @Delete}
     * 三者一致的分类规则）：
     *
     * <ul>
     *   <li>{@code @Where} 参数 → {@link CriteriaGenerator#parse} 递归展开为条件单元；
     *       顶层 {@code @UpdateSet} 字段（update 上下文）不在此列，由 {@code @Update}
     *       生成器另行解析为 SET 单元；</li>
     *   <li>其余参数 → {@link WhereCondition#resolveHost} 解析为单条件
     *       （字段名/操作符/动态判定/字段存在性校验）。</li>
     * </ul>
     *
     * @param info          仓库信息
     * @param method        声明式方法
     * @param entities      可按 {@code @Condition(entity)} 引用的实体模型表（键 = 实体
     *                      全限定名）；宿主实体必须在内，连接实体由 {@code @Select} 的
     *                      {@code @Join} 解析加入。无多表场景传 {@code Map.of()}
     * @param updateContext 是否处于 {@code @Update} 上下文（见
     *                      {@link CriteriaGenerator#parse(JForgeProcessor.DaoInfo,
     *                      ExecutableElement, VariableElement, boolean)}）
     * @return WHERE 部分解析结果；任一参数校验失败已报错返回 {@code null}
     */
    protected WhereParts resolveWhereParts(JForgeProcessor.DaoInfo info, ExecutableElement method,
            Map<String, EntityModel> entities, boolean updateContext) {
        WhereParts parts = new WhereParts();
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(Where.class) != null) {
                if (Nullability.isNullableParameter(parameter)) {
                    parts.criteriaNullable = true;
                }
                List<CriteriaGenerator.Unit> units =
                        criteriaGenerator.parse(info, method, parameter, updateContext);
                if (units == null) {
                    return null;
                }
                parts.criteriaUnits.addAll(units);
                continue;
            }
            WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                    processingEnv, "@Condition", entities, false);
            if (condition == null) {
                return null;
            }
            parts.conditions.add(condition);
        }
        return parts;
    }

    /**
     * 为方法的全部非空契约参数生成方法顶部的快速失败检查
     * （{@code Objects.requireNonNull(param, "param must not be null")}）——
     * null 直送会静默绑 NULL 或深处抛模糊 NPE（数组/集合的动态路径在占位符
     * 拼接处已有检查，顶层不重复）。
     *
     * @param spec   方法构建器（检查追加到当前语句流）
     * @param method 声明式方法
     */
    protected void emitTopLevelRequireNonNull(MethodSpec.Builder spec, ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            if (WhereCondition.needsRequireNonNull(parameter, processingEnv)) {
                spec.addStatement("$T.requireNonNull($N, $S)", Objects.class,
                        parameter.getSimpleName(), parameter.getSimpleName() + " must not be null");
            }
        }
    }

    /**
     * 全静态判定：WHERE 无动态参数，且条件对象（若存在）参数非空、字段全静态。
     * 成立时可折叠为完整 SQL 常量字段 + 静态索引绑定（运行时零拼接）。
     *
     * @param parts WHERE 部分解析结果
     * @return 全部条件可编译期确定时 {@code true}
     */
    protected static boolean allStatic(WhereParts parts) {
        return !parts.criteriaNullable
                && parts.conditions.stream().allMatch(WhereCondition::staticCompatible)
                && CriteriaGenerator.staticCompatible(parts.criteriaUnits);
    }

    /**
     * 把全静态的 WHERE 部分折叠进 SQL 文本（追加到 {@code sql}）：
     * 直接条件经 {@link WhereCondition#appendStaticWhereSql} 拼接；条件对象组
     * 前导连接符与动态形态一致（无直接条件时首个得 WHERE），组整体括号包裹、
     * 嵌套 {@code @Where} 递归（{@code allStatic} 保证组恒非空）。
     *
     * @param sql   待追加的 SQL 文本（已含语句头）
     * @param parts WHERE 部分解析结果（调用方保证 {@link #allStatic} 为真）
     */
    protected static void appendStaticWhere(StringBuilder sql, WhereParts parts) {
        boolean hasDirect = !parts.conditions.isEmpty();
        if (hasDirect) {
            WhereCondition.appendStaticWhereSql(sql, parts.conditions);
        }
        if (!parts.criteriaUnits.isEmpty()) {
            sql.append(hasDirect ? " AND " : " WHERE ");
            sql.append("(");
            CriteriaGenerator.appendStaticSql(sql, parts.criteriaUnits);
            sql.append(")");
        }
    }

    /**
     * 追加全静态形态的 WHERE 绑定（编译期索引）。
     *
     * @param spec     方法构建器
     * @param parts    WHERE 部分解析结果（调用方保证 {@link #allStatic} 为真）
     * @param index    WHERE 段首个占位符的起始索引（此前语句段已绑定的占位符数 + 1）
     */
    protected static void appendStaticWhereBinds(MethodSpec.Builder spec, WhereParts parts, int index) {
        WhereCondition.appendStaticBinds(spec, parts.conditions, index);
        if (!parts.criteriaUnits.isEmpty()) {
            CriteriaGenerator.appendStaticBinds(spec, parts.criteriaUnits,
                    index + WhereCondition.staticBindCount(parts.conditions));
        }
    }

    /**
     * 动态形态的静态前缀折叠：条件序列头部连续的全静态条件（无 null 守卫/Optional/
     * 集合）在编译期并入 SQL 常量字段，运行时 {@code StringBuilder} 从常量起拼——
     * 不再逐调用重复拼接恒定前缀。前缀已含 WHERE，"WHERE 可能整体缺失"的守卫无需再生成。
     *
     * @param parts WHERE 部分解析结果
     * @return 头部连续全静态条件的数量（0 = 无可折叠前缀）
     */
    protected static int staticPrefixCount(WhereParts parts) {
        int staticPrefix = 0;
        while (staticPrefix < parts.conditions.size()
                && parts.conditions.get(staticPrefix).staticCompatible()) {
            staticPrefix++;
        }
        return staticPrefix;
    }

    /**
     * 把静态前缀（头部连续的全静态条件）折叠成 SQL 常量字段，并在方法体内初始化
     * {@code StringBuilder sql} 从该常量（或 {@code baseSql}）起拼。
     *
     * @param spec          方法构建器
     * @param builder       接收方法的 impl 类构建器（常量字段挂到生成类）
     * @param parts         WHERE 部分解析结果
     * @param baseSql       语句头（含 FROM/JOIN 的部分）
     * @param methodName    方法名（常量字段名前缀）
     * @param overloadIndex 同名方法序号（字段名唯一性）
     * @return 折叠的静态条件数量（0 = 无可折叠前缀，{@code sql} 直接以 {@code baseSql} 初始化）。
     *         非 0 时前缀已消费首个 WHERE，调用方的 {@code where} 变量应以 {@code " AND "}
     *         起（剩余动态段从 AND 续拼）
     */
    protected int emitPrefixedStringBuilder(MethodSpec.Builder spec, TypeSpec.Builder builder,
            WhereParts parts, String baseSql, String methodName, int overloadIndex) {
        int staticPrefix = staticPrefixCount(parts);
        if (staticPrefix > 0) {
            StringBuilder prefix = new StringBuilder(baseSql);
            WhereCondition.appendStaticWhereSql(prefix, parts.conditions.subList(0, staticPrefix));
            String prefixField = methodName + "PrefixSql"
                    + (overloadIndex > 0 ? "_" + overloadIndex : "");
            builder.addField(FieldSpec.builder(String.class, prefixField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", prefix.toString()).build());
            spec.addStatement("$T sql = new $T($L)", ClassName.get(StringBuilder.class),
                    ClassName.get(StringBuilder.class), prefixField);
            return staticPrefix;
        }
        spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                ClassName.get(StringBuilder.class), baseSql);
        return 0;
    }

    /**
     * 为全静态形态添加 SQL 常量字段（{@code private static final String <方法名>Sql}，
     * 名称经 {@link SqlFieldGenerator#methodSqlFieldName} 处理同名重载）。
     *
     * @param builder       接收方法的 impl 类构建器
     * @param sql           折叠后的完整 SQL
     * @param methodName    方法名
     * @param overloadIndex 同名方法序号
     */
    protected static void addStaticSqlField(TypeSpec.Builder builder, String sql,
            String methodName, int overloadIndex) {
        String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
        builder.addField(FieldSpec.builder(String.class, sqlField,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", sql).build());
    }
}
