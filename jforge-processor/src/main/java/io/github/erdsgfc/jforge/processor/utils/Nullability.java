package io.github.erdsgfc.jforge.processor.utils;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Objects;

/**
 * JSpecify 空性判定的公共工具——实体列、生成类型与动态 SQL 参数统一走这里。
 */
public final class Nullability {

    private static final String NULLABLE = "org.jspecify.annotations.Nullable";
    private static final String NON_NULL = "org.jspecify.annotations.NonNull";
    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private Nullability() {
    }

    /**
     * 判定声明处类型是否可空。显式 {@code @Nullable} 优先于 {@code @NonNull}；
     * 无显式标注时沿声明的 enclosing 链查找 {@code @NullMarked}：作用域内默认非空，
     * 作用域外未标注引用类型默认可空。
     */
    public static boolean isNullable(Element declaration, TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return false;
        }
        if (hasAnnotation(type, NULLABLE) || hasAnnotation(declaration, NULLABLE)) {
            return true;
        }
        if (hasAnnotation(type, NON_NULL) || hasAnnotation(declaration, NON_NULL)) {
            return false;
        }
        return !isNullMarked(declaration);
    }

    /**
     * 参数是否标注 {@code @Nullable}:类型注解 + 声明注解双查——class 文件证实 @Nullable
     * 落在 METHOD_FORMAL_PARAMETER 的类型注解上,但与 {@code @Bind} 等声明注解混排时
     * javac 的归类可能不同,声明镜像也查一遍。
     */
    public static boolean isNullableParameter(VariableElement parameter) {
        return isNullable(parameter, parameter.asType());
    }

    /** 声明是否位于 JSpecify {@code @NullMarked} 作用域。 */
    public static boolean isNullMarked(Element declaration) {
        Element current = declaration;
        while (current != null) {
            if (hasAnnotation(current, NULL_MARKED)) {
                return true;
            }
            current = current.getEnclosingElement();
        }
        return false;
    }

    // ---- 空性代码生成辅助（非空契约参数/返回值的补标与快速失败）----

    /**
     * 返回补标 JSpecify {@code @NonNull} 的类型（非空契约的生成签名用）——
     * {@link TypeNameUtils#withNullability} 的语义化包装;与 {@link #isNullable}
     * 等判定方法区分:本族是"生成新类型",判定族是"读注解"。
     */
    public static TypeName withNonNull(TypeName type) {
        return TypeNameUtils.withNullability(type, false);
    }

    /** 返回补标 JSpecify {@code @Nullable} 的类型（可空契约的生成签名用）。 */
    public static TypeName withNullable(TypeName type) {
        return TypeNameUtils.withNullability(type, true);
    }

    /**
     * 在生成方法体开头生成 {@code Objects.requireNonNull(expr, "<operation>: <name> must
     * not be null")}——对 {@code @NonNull} 契约参数快速失败(null 是调用错误),替代静默
     * 返回或 null 在绑定/解引用处的模糊 NPE。消息带操作名以区分同名参数
     * (如 {@code id} 出现在 deleteById/findById/existsById)。
     *
     * @param method     方法构建器
     * @param operation  操作名(消息上下文,如 {@code "findById"})
     * @param expression 参数表达式(通常为参数名)
     * @param name       参数名(消息内容)
     */
    public static void requireNonNull(MethodSpec.Builder method, String operation, String expression,
            String name) {
        method.addStatement("$T.requireNonNull($L, $S)", Objects.class, expression,
                operation + ": " + name + " must not be null");
    }

    private static boolean hasAnnotation(Element element, String annotationName) {
        return element != null && element.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(annotationName));
    }

    private static boolean hasAnnotation(TypeMirror type, String annotationName) {
        return type.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(annotationName));
    }
}
