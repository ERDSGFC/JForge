package io.github.erdsgfc.jforge.processor.utils;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * JSpecify {@code @Nullable} 判定的公共工具——实体列空性、@Query/@Select 动态条件的
 * 空性判定统一走这里,避免注解镜像遍历逻辑在各生成器间漂移。
 */
public final class Nullability {

    private static final String NULLABLE = "org.jspecify.annotations.Nullable";

    private Nullability() {
    }

    /** 类型是否标注 JSpecify {@code @Nullable}（TYPE_USE 注解镜像遍历,不经类加载）。 */
    public static boolean isNullable(TypeMirror type) {
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
    public static boolean isNullableParameter(VariableElement parameter) {
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

    /** 是否为 java.lang 包装类(Integer/Long/Boolean/Double/Float/Short/Byte/Character)。 */
    public static boolean isBoxed(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String name = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        return switch (name) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Boolean",
                 "java.lang.Double", "java.lang.Float", "java.lang.Short",
                 "java.lang.Byte", "java.lang.Character" -> true;
            default -> false;
        };
    }
}
