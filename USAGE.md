# JForge 使用文档

编译期代码生成、运行时零反射的 Java ORM。本文档覆盖从依赖引入到 Spring 集成的完整用法。

---

## 1. 引入依赖

```xml
<!-- jforge-core 传递依赖 jforge-annotation(注解定义),无需单独引入 -->
<dependency>
    <groupId>io.github.erdsgfc</groupId>
    <artifactId>jforge-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
<!-- 注解处理器(编译期生效,不进入运行时) -->
<dependency>
    <groupId>io.github.erdsgfc</groupId>
    <artifactId>jforge-processor</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
<!-- 可选:Spring Boot 集成 -->
<dependency>
    <groupId>io.github.erdsgfc</groupId>
    <artifactId>jforge-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

需要 SLF4J 实现(日志门面,jforge-core 只依赖 `slf4j-api`)。

## 2. 定义实体

实体是**接口**:属性 getter + builder setter(同名、单参数、返回实体接口)。

```java
@Table(name = "users")
public interface UserEntity {

    @Id @GeneratedValue
    Long id();
    UserEntity id(Long id);

    @Column(name = "user_name")   // 显式列名;缺省时用 @JForgeConfig.naming 策略推断
    String name();
    UserEntity name(String name);

    Integer age();
    UserEntity age(Integer age);

    // 辅助逻辑用 default/static 方法——不会被映射成列
    default String displayName() {
        return "user:" + name();
    }
}
```

**属性注解**(标在 getter 上):

| 注解 | 用途 |
|---|---|
| `@Table(name=...)` | 表名(**可选**);缺省按类名推断为 snake_case(`UserEntity` → `user_entity`) |
| `@Id` | 主键(必填且唯一) |
| `@GeneratedValue` | 数据库自增,插入后回写主键 |
| `@Column(name=...)` | 显式列名 |

**接口契约**(处理器编译期校验,违规直接报错):

- 每个方法必须是 getter(无参非 void)、builder setter(单参返回接口自身)、或 `static`/`default` 方法
- setter 必须有同名 getter,且参数类型与 getter 返回类型一致
- 列名不能重复

## 3. 定义仓库

仓库是继承 `BaseRepository<T, ID>` 的接口(必须**直接继承**,不支持间接继承/泛型中间层):

```java
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** 自定义查询:命名占位符 :name 绑定到 @Bind 参数 */
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    /** 投影到 DTO record(按 SELECT 列序对应 record 组件) */
    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);

    /** 标量返回值 */
    @Query("SELECT COUNT(*) FROM users WHERE age = :age")
    long countByAge(@Bind("age") int age);

    /** 写语句返回影响行数 */
    @Query("UPDATE users SET age = :age WHERE id = :id")
    int updateAge(@Bind("id") long id, @Bind("age") int age);
}
```

**继承的 CRUD**(`BaseRepository<T, ID>`):`save`(单条/批量)、`delete`、`deleteById`、`deleteByIds`、`update`、`findById`、`findByIds`、`findAll`、`count`、`existsById`、`createEntity`。

**@Query 规则**:

- 占位符 `:name` ↔ `@Bind("name")` 参数一一对应
- SELECT → 按返回类型映射:实体(按列名)、DTO record(按列序)、标量、List 包装
- 非 SELECT → 返回影响行数;配合 `@ReturnGeneratedKeys` 可回写生成键到实体参数
- 每方法只能有一个语句注解

## 4. 配置

`@JForgeConfig` 放在包的 `package-info.java`(**包级策略**,不支持元素级):

```java
@JForgeConfig(
    dialect = Dialect.POSTGRESQL,
    naming = NamingStrategy.CAMEL_TO_SNAKE,   // userName → user_name
    generatedPackage = "com.example.data.generated",
    implSuffix = "Impl",
    springBeans = true,
    logSql = false,
    batchSize = 100
)
package com.example.data;
```

| 属性 | 默认 | 说明 |
|---|---|---|
| `dialect` | POSTGRESQL | SQL 方言 |
| `naming` | NONE | 列名推断策略(无 @Column 时) |
| `generatedPackage` | 空(实体同包) | 生成类输出包 |
| `implSuffix` | `_Impl` | 生成类后缀(实体/仓库一致) |
| `springBeans` | false | 生成 `@Repository` + `@Autowired` 构造器 |
| `logSql` | false | 生成 SQL DEBUG/WARN 日志代码(开启才有日志开销) |
| `batchSize` | 50 | `save(List)` 批处理分块大小;0 关闭 |

**批处理粒度覆盖**:`@BatchSize(N)` 标在仓库接口(类型级)或重声明的 `save(List<T>)` 方法(方法级)。优先级:方法级 > 类型级 > `@JForgeConfig.batchSize` > 默认 50。

## 5. 获取仓库

统一门面 `JForge`(持有 `DataSource`/`TransactionManager`,缓存仓库):

```java
JForge jforge = new JForge(dataSource);                       // 内置 SimpleTransactionManager
// 或指定管理器(如 Spring 包装器)
JForge jforge = new JForge(dataSource, new SpringTransactionManager(txManager));

UserRepository repo = jforge.repository(UserRepository.class);  // 缓存,同类型同一实例
```

## 6. 编程式事务

线程级事务:同一线程、同一 `DataSource` 的仓库共享连接与事务边界;不支持嵌套。

**模板**(推荐,自动提交/回滚):

```java
// 有返回值,回调拿事务连接做原生控制
Long id = repo.execute(conn -> {
    UserEntity u = repo.save(repo.createEntity().name("a").age(1));
    conn.setSavepoint("sp");                     // 原生 JDBC
    if (/* 业务判定 */) repo.markRollbackOnly(); // 条件性回滚,回调正常返回
    return u.id();
});

// 外部参数 / 无返回值 / 不暴露连接 —— 全组合 8 个变体
repo.execute("param", (conn, p) -> { ... });          // 参数 + 连接 + 返回
repo.execute(() -> { ...; return x; });               // 无连接 + 返回
repo.run(conn -> { ... });                            // 连接 + 无返回
repo.run("param", p -> { ... });                      // 参数 + 无连接 + 无返回
```

**手动控制**:

```java
repo.beginTransaction();     // 返回事务绑定连接
repo.save(entity);
repo.commit();               // 或 repo.rollback()
```

`markRollbackOnly()`:标记回滚但不抛异常,回调正常返回后事务在完成点回滚;`isRollbackOnly()` 查询。

## 7. 连接作用域(无事务共享连接)

借用 1 个连接供多个仓库调用共享:**autocommit 不变、无原子性**(异常前的语句保持已提交),用于"多 SQL 只需省池往返":

```java
repo.executeWithoutTransaction(conn -> {
    repo.save(a);
    repo.save(b);            // 与上面共享同一连接
    return null;
});
repo.runWithoutTransaction(() -> { ... });   // 无连接变体
```

规则:嵌套作用域复用外层连接;事务内开作用域退化为 no-op(复用事务连接);作用域内 `beginTransaction()` 抛 `JForgeException`。

## 8. JDBC 批处理

```java
List<UserEntity> list = ...;
repo.save(list);   // 单连接 + addBatch/executeBatch,默认每 50 行 flush 一次,生成键按插入序回写
```

`batchSize` 覆盖见第 4 节。注意:生成键批处理依赖驱动支持(H2/PostgreSQL 支持;MySQL Connector/J 旧版只返回批量最后一条的键)。

## 9. 错误处理

所有失败抛 `JForgeException`(RuntimeException),带错误码分类:

```java
try {
    repo.findById(1L);
} catch (JForgeException e) {
    switch (e.code()) {          // CONNECTION / SQL / TRANSACTION / MAPPING / CONFIGURATION
        case SQL -> log.error("SQL failed: {}", e.sql());   // 失败的 SQL 语句
        ...
    }
}
```

生成代码的错误消息内嵌操作名、表名和 SQL,根因一目了然。

## 10. Spring Boot 集成

引入 starter 后自动配置注册 `SpringTransactionManager` bean,三种方式直接控制仓库操作:

```java
// 方式一:@Transactional(声明式)
@Transactional
public void createUser() {
    repo.save(...);
}

// 方式二:TransactionTemplate
transactionTemplate.execute(status -> repo.save(...));

// 方式三:PlatformTransactionManager 手动
TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
repo.save(...);
txManager.commit(tx);
```

**两种接线方式**:

1. **Spring bean 注入**(`@JForgeConfig(springBeans = true)`):生成的 `XxxRepository_Impl` 标 `@Repository` + `@Autowired(DataSource, TransactionManager)` 构造器,组件扫描自动注册
2. **门面手动接线**:`new JForge(ds, new SpringTransactionManager(ptm)).repository(...)`

仓库持有注入的 `SpringTransactionManager`,其 `connection()`/`release()` 委托 `DataSourceUtils`,自动 join 外层 Spring 事务。

## 11. 已知限制

- `@Transactional(timeout=…)` 对生成代码**不生效**(Spring 靠 `JdbcTemplate` 强制执行,生成代码不经 JdbcTemplate)
- 嵌套 ORM 事务不支持(嵌套 begin 抛异常);Spring 环境下 begin 加入外层事务(PROPAGATION_REQUIRED)
- `@Dao` 必须直接继承 `BaseRepository<T, ID>`
- 批处理生成键依赖驱动支持(见第 8 节)

## 12. 性能

ORM 与手写裸 JDBC 等效:实测(10×2s,H2 + HikariCP)FindAll +3.9%、FindById +1.0%、Insert −1.6%(噪声内)。详见 `BENCHMARK_RESULTS.md` Run ORM-1。
