package io.github.erdsgfc.jforge.processor;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * JSpecify {@code @Nullable} 判定的公共工具——实体列空性、@Query/@Select 动态条件的
 * 空性判定统一走这里,避免注解镜像遍历逻辑在各生成器间漂移。
 */
final class Nullability {

    private static final String NULLABLE = "org.jspecify.annotations.Nullable";

    private Nullability() {
    }

    /** 类型是否标注 JSpecify {@code @Nullable}（TYPE_USE 注解镜像遍历,不经类加载）。 */
    static boolean isNullable(TypeMirror type) {
        for (var mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(NULLABLE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 参数是否标注 {@code @Nullable}:类型注解 + 声明注解双查——class 文件证实 @Nullable
     * 落在 METHOD_FORMAL_PARAMETER 的类型注解上,但与 {@code @Bind} 等声明注解混排时
     * javac 的归类可能不同,声明镜像也查一遍。
     */
    static boolean isNullableParameter(VariableElement parameter) {
        if (isNullable(parameter.asType())) {
            return true;
        }
        for (var mirror : parameter.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(NULLABLE)) {
                return true;
            }
        }
        return false;
    }
}
