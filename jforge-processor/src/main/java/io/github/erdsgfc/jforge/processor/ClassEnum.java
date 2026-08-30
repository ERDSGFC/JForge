package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;

public enum ClassEnum {

    BASE_REPOSITORY("io.github.erdsgfc.jforge.core", "BaseRepository"),
    BASE_REPOSITORY_FACTORY("io.github.erdsgfc.jforge.generated", "Repositories"),
    TRANSACTION_MANAGER("io.github.erdsgfc.jforge", "TransactionManager"),
    ABSTRACT_REPOSITORY("io.github.erdsgfc.jforge.core", "AbstractRepository"),
    ORM_EXCEPTION("io.github.erdsgfc.jforge", "JForgeException"),
    JDBC_DATA_SOURCE("javax.sql", "DataSource"),
    JDBC_RESULT_SET("java.sql", "ResultSet"),
    JDBC_STATEMENT("java.sql", "Statement"),
    JDBC_PREPARED_STATEMENT("java.sql", "PreparedStatement"),
    JDBC_CONNECTION("java.sql", "Connection"),
    JDBC_SQLEXCEPTION("java.sql", "SQLException"),
    SPRING_REPOSITORY("org.springframework.stereotype", "Repository"),
    SPRING_AUTOWIRED("org.springframework.beans.factory.annotation", "Autowired"),
    SLF4J_LOGGER("org.slf4j", "Logger"),
    SLF4J_LOGGER_FACTORY("org.slf4j", "LoggerFactory"),
    ;

    ClassEnum(String packagePath, String className) {
        this.packagePath = packagePath;
        this.className = className;
        this.fullClassName = packagePath + "." + className;
        this.javaPoetClassName = ClassName.get(packagePath, className);
    }

    private final String packagePath;
    private final String className;
    private final String fullClassName;
    private final ClassName javaPoetClassName;
    public String getPackagePath() {
        return packagePath;
    }
    public String getClassName() {
        return className;
    }
    public String getFullClassName() {
        return fullClassName;
    }
    public ClassName getJavaPoetClassName() {
        return javaPoetClassName;
    }
}
