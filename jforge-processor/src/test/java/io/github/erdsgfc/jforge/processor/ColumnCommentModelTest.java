package io.github.erdsgfc.jforge.processor;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Column.comment()} 从注解到 {@link EntityModel.ColumnModel} 的接线验证。
 *
 * <p>{@code comment} 是纯元数据——不影响任何生成的 SQL（生成的 CRUD/JOIN 只含列名与
 * 占位符），因此无法从生成产物断言，必须直接检查解析出的实体模型。本测试用一个
 * 捕获型处理器在编译期把 {@code 列名 → comment} 抓出来。</p>
 */
class ColumnCommentModelTest {

    /** 在编译期解析实体模型，把 列名 → comment 存入静态表供断言。 */
    static final class CapturingProcessor extends AbstractProcessor {
        static final Map<String, String> COMMENTS = new HashMap<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("io.github.erdsgfc.jforge.annotation.Table");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return true;
            }
            JForgeConfigHelper config = new JForgeConfigHelper(processingEnv);
            for (Element element : roundEnv.getRootElements()) {
                if (element.getKind() != ElementKind.INTERFACE) {
                    continue;
                }
                TypeElement type = (TypeElement) element;
                EntityModel model = EntityModel.parse(type, processingEnv.getTypeUtils(),
                        Diagnostic.Kind.ERROR, processingEnv.getMessager(), config);
                if (model == null) {
                    continue;
                }
                COMMENTS.clear();
                for (EntityModel.ColumnModel column : model.columns()) {
                    COMMENTS.put(column.columnName, column.comment);
                }
            }
            return true;
        }
    }

    @Test
    void commentIsReadIntoColumnModel() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);

                    @Column(name = "user_name", comment = "用户姓名")
                    String name(); User name(String v);

                    @Column(name = "age")                 // 未填 comment → 空串
                    Integer age(); User age(Integer v);

                    String  nick(); User nick(String v);  // 无 @Column → 空串
                }
                """;
        CapturingProcessor.COMMENTS.clear();
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.User", source, new CapturingProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());

        assertEquals("用户姓名", CapturingProcessor.COMMENTS.get("user_name"),
                "显式 comment 应读入 ColumnModel");
        assertEquals("", CapturingProcessor.COMMENTS.get("age"),
                "@Column 未填 comment 时应为空串");
        assertEquals("", CapturingProcessor.COMMENTS.get("nick"),
                "无 @Column 的列 comment 应为空串");
    }
}
