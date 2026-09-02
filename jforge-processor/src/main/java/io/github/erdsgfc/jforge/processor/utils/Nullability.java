package io.github.erdsgfc.jforge.processor.utils;

import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

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

    private static boolean hasAnnotation(Element element, String annotationName) {
        return element != null && element.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(annotationName));
    }

    private static boolean hasAnnotation(TypeMirror type, String annotationName) {
        return type.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(annotationName));
    }
}
