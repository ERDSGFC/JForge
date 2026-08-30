package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.github.erdsgfc.jforge.processor.ClassEnum.*;

/**
 * 生成 {@code @Dao} 仓库的实现类 {@code XxxRepository_Impl}（继承 {@code AbstractRepository}，
 * 直写 JDBC 的 CRUD + {@code @Query}）。
 *
 * <p>本类是编排层：组装类的整体结构（类声明、Spring 注解、构造器），把宿主的实体 impl
 * 作为 {@code private static final} 嵌套类嵌入（{@link EntityGenerator}），并把其余生成职责
 * 委托给 {@link SqlFieldGenerator}（固定 SQL 字段）、{@link CrudGenerator}（CRUD + 行映射）与
 * {@link QueryGenerator}（{@code @Query} + 结果映射，必要时现场嵌入其他实体）。</p>
 */
final class RepositoryGenerator {


    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final CrudGenerator crudGenerator;
    private final QueryGenerator queryGenerator;
    private final SelectGenerator selectGenerator;
    private final UpdateGenerator updateGenerator;
    private final DeleteGenerator deleteGenerator;

    RepositoryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.crudGenerator = new CrudGenerator(configHelper);
        this.queryGenerator = new QueryGenerator(processingEnv, configHelper);
        this.selectGenerator = new SelectGenerator(processingEnv, configHelper, queryGenerator);
        this.updateGenerator = new UpdateGenerator(processingEnv, configHelper);
        this.deleteGenerator = new DeleteGenerator(processingEnv, configHelper);
    }

    /**
     * 为一个 {@code @Dao} 生成仓库实现类并写入源文件。
     *
     * @param info 已解析的仓库信息
     */
    void generate(JForgeProcessor.DaoInfo info) {
        TypeSpec typeSpec = buildImpl(info);
        try {
            JavaFile.builder(info.daoPackage, typeSpec)
                    .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(info.element, "Failed to generate " + info.implName + ": " + e.getMessage());
        }
    }

    /**
     * 组装仓库实现类：类声明、Spring 注解、构造器，然后委托各生成器产出固定 SQL 字段、
     * 行映射、CRUD 方法与 {@code @Query} 方法。
     *
     * @param info 已解析的仓库信息
     * @return 生成的类规格
     */
    private TypeSpec buildImpl(JForgeProcessor.DaoInfo info) {
        ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
        // 实体 impl 作为本仓库 impl 的嵌套类：全限定名为 daoPackage.ImplName.EntityImplName，
        // 仓库内部以简单名引用（mapRow/createEntity 的 new Xxx_Impl() 无需 import）。
        ClassName entityImpl = ClassName.get(info.daoPackage, info.implName,
                EntityModel.implNameOf(info.model.entitySimpleName(), info.model.implSuffix()));
        ClassName connection = JDBC_CONNECTION.getJavaPoetClassName();
        ClassName preparedStatement = JDBC_PREPARED_STATEMENT.getJavaPoetClassName();
        ClassName resultSet = JDBC_RESULT_SET.getJavaPoetClassName();
        ClassName sqlException = JDBC_SQLEXCEPTION.getJavaPoetClassName();

        // 适配 Spring Boot：配置 springBeans=true 时，生成的 impl 标 @Repository + @Autowired 构造器，
        // 并去掉 final（final 类无法被 Spring CGLIB 代理），由组件扫描自动注入容器。
        boolean springBeans = configHelper.springBeans(info.element);
        TypeSpec.Builder builder = TypeSpec.classBuilder(info.implName)
                .addModifiers(springBeans
                        ? new Modifier[] {Modifier.PUBLIC}
                        : new Modifier[] {Modifier.PUBLIC, Modifier.FINAL})
                .addSuperinterface(daoClass)
                .superclass(ABSTRACT_REPOSITORY.getJavaPoetClassName());
        if (springBeans) {
            builder.addAnnotation(AnnotationSpec.builder(SPRING_REPOSITORY.getJavaPoetClassName()).build());
        }
        // 宿主实体 impl 作为 private static final 嵌套类嵌入仓库 impl（私有嵌套类无法共享，
        // 多个仓库引用同一实体时各嵌一份副本）；embedded 表预置宿主实体，供 @Query 结果
        // 映射复用，并用于"非宿主实体"的现场嵌入去重。
        builder.addType(EntityGenerator.buildImpl(info.model));
        Map<String, QueryGenerator.EmbeddedEntity> embedded = new HashMap<>();
        embedded.put(info.model.entityQualifiedName(),
                new QueryGenerator.EmbeddedEntity(entityImpl, info.model));
        // 构造器注入 DataSource + TransactionManager 并传给父类 AbstractRepository
        //（父类持有 protected final 字段 + 连接/事务方法），impl 只留实体特定代码。
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JDBC_DATA_SOURCE.getJavaPoetClassName(), "dataSource")
                .addParameter(TRANSACTION_MANAGER.getJavaPoetClassName(), "transactionManager")
                .addStatement("super(dataSource, transactionManager)");
        if (springBeans) {
            constructor.addAnnotation(AnnotationSpec.builder(
                    SPRING_AUTOWIRED.getJavaPoetClassName()).build());
        }
        builder.addMethod(constructor.build());

        // 配置 logSql=true 时生成 SLF4J Logger 字段，SQL 方法里 emit DEBUG/WARN 日志；
        // 默认 false 不生成任何日志代码（保持与手写 JDBC 等效的零开销）。
        if (configHelper.logSql(info.element)) {
            builder.addField(FieldSpec.builder(
                    SLF4J_LOGGER.getJavaPoetClassName(), "log",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.getLogger($T.class)",
                            SLF4J_LOGGER_FACTORY.getJavaPoetClassName(),
                            ClassName.get(info.daoPackage, info.implName))
                    .build());
        }

        // 固定 SQL 常量字段（命名引用，避免方法体内散落字符串字面量）。
        for (FieldSpec field : SqlFieldGenerator.sqlFields(info, configHelper)) {
            builder.addField(field);
        }

        // 行映射 helper 与 CRUD 方法。
        builder.addMethod(crudGenerator.rowMapperMethod(info, entityImpl, sqlException, resultSet));
        builder.addMethod(crudGenerator.countByIdMethod(info, sqlException, connection, preparedStatement, resultSet));
        for (MethodSpec method : crudGenerator.crudMethods(info, entityImpl, connection, preparedStatement,
                resultSet, sqlException)) {
            builder.addMethod(method);
        }
        queryGenerator.queryMethods(info, builder, embedded, connection, preparedStatement, resultSet,
                sqlException);
        selectGenerator.selectMethods(info, builder, embedded, connection, preparedStatement, resultSet,
                sqlException);
        updateGenerator.updateMethods(info, builder, connection, preparedStatement, sqlException);
        deleteGenerator.deleteMethods(info, builder, connection, preparedStatement, sqlException);

        return builder.build();
    }

    /**
     * 上报绑定到指定元素的编译期错误。
     *
     * @param element 出错的元素
     * @param message 错误消息
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
