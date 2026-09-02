package io.github.erdsgfc.jforge.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;
import io.github.erdsgfc.jforge.processor.generator.core.RepositoryGenerator;
import io.github.erdsgfc.jforge.processor.utils.CommonUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.erdsgfc.jforge.processor.ClassEnum.*;

/**
 * JForge 的注解处理器入口：只处理 {@code @Dao} 仓库接口。
 *
 * <p>每个 {@code @Dao} 通过 {@code BaseRepository<T, ID>} 的类型参数定位实体，处理器顺带生成：
 * <ul>
 *   <li>仓库的 {@code XxxRepository_Impl}（CRUD + {@code @Query} + 事务方法 + SQL 常量字段，委托给
 *       {@link RepositoryGenerator}），其实体的 {@code Xxx_Impl} 作为 {@code private static final}
 *       嵌套类随仓库文件一起生成——多仓库共享同一实体时各仓库各嵌一份副本；</li>
 *   <li>每包一个的 {@code Repositories} 工厂。</li>
 * </ul>
 * 实体上的 {@code @Table} 仍是必需的声明注解（提供表名/列映射，由 {@link EntityModel} 读取），
 * 但不再需要单独的实体处理器。</p>
 */
@AutoService(javax.annotation.processing.Processor.class)
@SupportedAnnotationTypes({
    "io.github.erdsgfc.jforge.annotation.Dao",
    "io.github.erdsgfc.jforge.annotation.JForgeConfig"
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class JForgeProcessor extends AbstractProcessor {

    /** 已生成仓库 impl 的全限定名：显式去重，不依赖 javac"每轮输入只含本轮文件"的隐式行为。 */
    private final Set<String> generatedRepositories = new HashSet<>();
    private final List<DaoInfo> daos = new ArrayList<>();
    private int lastFactoriesSize;

    private JForgeConfigHelper configHelper;
    private RepositoryGenerator repositoryGenerator;

    /** 一个 {@code @Dao} 仓库的解析结果：实体/id 类型、实体模型、生成类名。 */
    public static final class DaoInfo {
        public TypeElement element;
        public String daoQualifiedName;
        public String daoSimpleName;
        public String daoPackage;
        public TypeName entityType;
        public TypeName idType;
        public EntityModel model;
        public String implName;
        public boolean springBean = false;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        configHelper = new JForgeConfigHelper(processingEnv);
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
        // 每轮开头一次性收集全部 @JForgeConfig(package-info),后续查询只查表 +
        // 字符串前缀继承,不依赖元素模型的包层次导航。
        configHelper.collect(roundEnv.getElementsAnnotatedWith(JForgeConfig.class));
        for (Element element : roundEnv.getElementsAnnotatedWith(Dao.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            DaoInfo info = parseDao((TypeElement) element);
            if (info == null) {
                continue;
            }

            String implQualifiedName = info.daoPackage.isEmpty()
                    ? info.implName
                    : info.daoPackage + "." + info.implName;
            if (generatedRepositories.add(implQualifiedName)) {
                repositoryGenerator.generate(info);
            }
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
        info.daoPackage = CommonUtils.packageOf(info.daoQualifiedName);
        info.implName = info.daoSimpleName + configHelper.implSuffix(dao);

        TypeMirror entityMirror = null;
        TypeMirror idMirror = null;
        for (TypeMirror iface : dao.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) iface;
                TypeElement ifaceElement = (TypeElement) declared.asElement();
                if (ifaceElement.getQualifiedName().contentEquals(BASE_REPOSITORY.getFullClassName())
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

        // 实体类型参数必须是具体类型:泛型 @Dao 接口(如 BaseRepository<T, Long>)解析出的
        // 是类型变量,直接 cast 会抛 ClassCastException 让处理器崩溃——这里转成友好报错。
        if (entityMirror.getKind() != TypeKind.DECLARED) {
            error(dao, "Entity type parameter must be a concrete type (generic @Dao interfaces are not supported): "
                    + entityMirror);
            return null;
        }
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityMirror).asElement();

        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return null;
        }

        // ID 类型以实体 @Id getter 为准(实体自己声明主键类型,泛型参数可能写错);
        // 泛型参数仅作一致性校验——不一致直接报错,而不是静默采用错误类型。
        if (!processingEnv.getTypeUtils().isSameType(idMirror, model.idColumn().returnType)) {
            error(dao, "@Dao ID type " + idMirror + " does not match entity @Id getter type "
                    + model.idColumn().returnType + " on " + entityElement.getQualifiedName());
            return null;
        }
        // ID 类型参数同样必须是具体类型:虽然 idMirror 从不被 cast(isSameType 对类型变量
        // 安全比较返回 false),但显式判断能让泛型 ID 的错误消息准确,而不是误导性的
        // "ID 类型不匹配"。
        if (idMirror.getKind() != TypeKind.DECLARED) {
            error(dao, "ID type parameter must be a concrete type (generic @Dao interfaces are not supported): "
                    + idMirror);
            return null;
        }

        info.model = model;
        info.entityType = ClassName.get(model.entityPackage(), model.entitySimpleName());
        info.idType = TypeName.get(model.idColumn().returnType);
        return info;
    }

    /**
     * 生成固定包 {@code io.github.erdsgfc.jforge.generated.Repositories}：一个
     * {@code create(Class, DataSource, TransactionManager)} 静态方法，按仓库接口类型分发到对应
     * 生成实现（把 DataSource + TransactionManager 传给 impl 构造器）。框架 jar 自带同包同名
     * 空壳占位类，运行时用户 target/classes 的真实实现按类加载优先级覆盖占位。
     */
    private void writeFactories() {
        if (daos.isEmpty()) {
            return;
        }
        TypeVariableName t = TypeVariableName.get("T");
        MethodSpec.Builder create = MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(t)
                .returns(t)
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), t), "type")
                .addParameter(JDBC_DATA_SOURCE.getJavaPoetClassName(), "dataSource")
                .addParameter(TRANSACTION_MANAGER.getJavaPoetClassName(), "transactionManager")
                // 分发的 (T) cast 是确定安全的（type 与返回的 impl 一一对应），抑制 unchecked 警告。
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build());
        // if/else-if/else 链：分支互斥语义显式化（每个分支都 return，独立的 if 行为等价，
        // 但 else-if 防止未来改动破坏互斥），最后的 else 兜底未匹配类型。
        boolean first = true;
        for (DaoInfo info : daos) {
            if (!info.springBean) {
                if (first) {
                    create.beginControlFlow("if (type == $T.class)", ClassName.get(info.daoPackage, info.daoSimpleName));
                    first = false;
                } else {
                    create.nextControlFlow("else if (type == $T.class)", ClassName.get(info.daoPackage, info.daoSimpleName));
                }
                create.addStatement("return ($T) new $T(dataSource, transactionManager)", t,
                        ClassName.get(info.daoPackage, info.implName));
            }
        }
        if (first) {
            create.addStatement("throw new $T($S + type.getName())", IllegalArgumentException.class,
                            "No generated repository for type: ");
        } else  {
            create.nextControlFlow("else")
                    .addStatement("throw new $T($S + type.getName())", IllegalArgumentException.class,
                            "No generated repository for type: ")
                    .endControlFlow();
        }

        TypeSpec.Builder factories = TypeSpec.classBuilder(BASE_REPOSITORY_FACTORY.getClassName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(create.build());
        try {
            JavaFile.builder(BASE_REPOSITORY_FACTORY.getPackagePath(), factories.build())
                    .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(null, "Failed to generate Repositories: " + e.getMessage());
        }
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
