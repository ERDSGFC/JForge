package io.github.erdsgfc.jforge.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Table;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JForge 的注解处理器入口：只处理 {@code @Dao} 仓库接口。
 *
 * <p>每个 {@code @Dao} 通过 {@code BaseRepository<T, ID>} 的类型参数定位实体，处理器顺带生成：
 * <ul>
 *   <li>实体的 {@code Xxx_Impl}（按实体全限定名去重——多个仓库共享同一实体时只生成一次），委托给
 *       {@link EntityGenerator}；</li>
 *   <li>仓库的 {@code XxxRepository_Impl}（CRUD + {@code @Query} + 事务方法 + SQL 常量字段），委托给
 *       {@link RepositoryGenerator}；</li>
 *   <li>每包一个的 {@code Repositories} 工厂。</li>
 * </ul>
 * 实体上的 {@code @Table} 仍是必需的声明注解（提供表名/列映射，由 {@link EntityModel} 读取），
 * 但不再需要单独的实体处理器。</p>
 */
@AutoService(javax.annotation.processing.Processor.class)
@SupportedAnnotationTypes("io.github.erdsgfc.jforge.annotation.Dao")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class JForgeProcessor extends AbstractProcessor {

    private static final String BASE_REPOSITORY = "io.github.erdsgfc.jforge.core.BaseRepository";

    /** 已生成实体 impl 的全限定名，避免多个仓库共享同一实体时重复生成。 */
    private final Set<String> generatedEntities = new HashSet<>();
    private final List<DaoInfo> daos = new ArrayList<>();
    private int lastFactoriesSize;

    private OrmConfigHelper configHelper;
    private EntityGenerator entityGenerator;
    private RepositoryGenerator repositoryGenerator;

    /** 一个 {@code @Dao} 仓库的解析结果：实体/id 类型、实体模型、生成类名。 */
    static final class DaoInfo {
        TypeElement element;
        String daoQualifiedName;
        String daoSimpleName;
        String daoPackage;
        TypeName entityType;
        TypeName idType;
        String idTypeName;
        EntityModel model;
        String implName;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        configHelper = new OrmConfigHelper(processingEnv);
        entityGenerator = new EntityGenerator(processingEnv, configHelper, generatedEntities);
        repositoryGenerator = new RepositoryGenerator(processingEnv, configHelper);
    }

    /**
     * 扫描当前轮的 {@code @Dao} 接口：先生成实体 impl（去重），再生成仓库 impl，最后按包生成工厂。
     *
     * @param annotations the annotation types requested by this processor
     * @param roundEnv    the current processing round
     * @return {@code true} (the @Dao annotation is claimed by this processor)
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(Dao.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            DaoInfo info = parseDao((TypeElement) element);
            if (info == null) {
                continue;
            }
            // 实体 impl 先于仓库 impl 生成（仓库生成代码引用实体 impl 的类名）。
            entityGenerator.generate(info.model);
            repositoryGenerator.generate(info);
            daos.add(info);
        }
        if (daos.size() != lastFactoriesSize) {
            writeFactories();
            lastFactoriesSize = daos.size();
        }
        return true;
    }

    /**
     * 解析一个 {@code @Dao}：从 {@code BaseRepository<T, ID>} 的类型参数解析实体/id 类型，
     * 并解析实体的 {@link EntityModel}（校验 {@code @Table}）。
     *
     * @param dao the {@code @Dao} repository interface
     * @return 解析结果，或 {@code null}（校验失败，已报错）
     */
    private DaoInfo parseDao(TypeElement dao) {
        DaoInfo info = new DaoInfo();
        info.element = dao;
        info.daoQualifiedName = dao.getQualifiedName().toString();
        info.daoSimpleName = dao.getSimpleName().toString();
        info.daoPackage = packageOf(info.daoQualifiedName);
        info.implName = info.daoSimpleName + "_Impl";

        TypeMirror entityMirror = null;
        TypeMirror idMirror = null;
        for (TypeMirror iface : dao.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) iface;
                TypeElement ifaceElement = (TypeElement) declared.asElement();
                if (ifaceElement.getQualifiedName().contentEquals(BASE_REPOSITORY)
                        && declared.getTypeArguments().size() == 2) {
                    entityMirror = declared.getTypeArguments().get(0);
                    idMirror = declared.getTypeArguments().get(1);
                }
            }
        }
        if (entityMirror == null || idMirror == null) {
            error(dao, "@Dao interface must extend BaseRepository<T, ID>");
            return null;
        }

        TypeElement entityElement = (TypeElement) ((DeclaredType) entityMirror).asElement();
        if (entityElement.getAnnotation(Table.class) == null) {
            error(dao, "Entity type " + entityElement.getQualifiedName() + " must be annotated with @Table");
            return null;
        }
        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return null;
        }

        info.model = model;
        info.entityType = ClassName.get(model.entityPackage(), model.entitySimpleName());
        info.idType = TypeNameUtils.toTypeName(idMirror.toString());
        info.idTypeName = idMirror.toString();
        return info;
    }

    /**
     * 每包生成一个 {@code Repositories} 工厂，含每个仓库的 {@code createXxxRepository(DataSource)}。
     */
    private void writeFactories() {
        Map<String, List<DaoInfo>> byPackage = new LinkedHashMap<>();
        for (DaoInfo info : daos) {
            byPackage.computeIfAbsent(info.daoPackage, p -> new ArrayList<>()).add(info);
        }
        for (Map.Entry<String, List<DaoInfo>> entry : byPackage.entrySet()) {
            TypeSpec.Builder factories = TypeSpec.classBuilder("Repositories")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            ClassName dataSource = ClassName.get("javax.sql", "DataSource");
            for (DaoInfo info : entry.getValue()) {
                ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
                factories.addMethod(MethodSpec.methodBuilder("create" + info.daoSimpleName)
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(daoClass)
                        .addParameter(dataSource, "dataSource")
                        .addStatement("return new $T(dataSource)", ClassName.get(info.daoPackage, info.implName))
                        .build());
            }
            try {
                JavaFile.builder(entry.getKey(), factories.build())
                        .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                        .build()
                        .writeTo(processingEnv.getFiler());
            } catch (IOException e) {
                error(null, "Failed to generate Repositories: " + e.getMessage());
            }
        }
    }

    /**
     * 从全限定名提取包名。
     *
     * @param qualifiedName the fully qualified name
     * @return the package name, or an empty string for the default package
     */
    private static String packageOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    /**
     * 上报编译期错误（绑定到元素；元素为 null 时全局）。
     *
     * @param element the offending element, or {@code null}
     * @param message the error message
     */
    void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
