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

实体接口可以**继承父接口**——父接口声明的属性同样参与映射(`@Id/@Column/@GeneratedValue`
标注在父接口上照常生效),列顺序 = 继承层次顺序(子接口自身属性在前,父接口属性在后)。
父接口支持**泛型自限定模式**(CRTP),类型参数**必须且只能有一个、且必须是子接口自身**:

```java
interface BaseEntity<T extends BaseEntity<T>> {
    @Id @GeneratedValue Long id();
    T id(Long id);
    String name();
    T name(String name);
}

@Table(name = "users")
interface UserEntity extends BaseEntity<UserEntity> {
    Integer age();
    UserEntity age(Integer age);
}
```

泛型实参替换后,父接口的 builder setter 返回子接口类型(`T name(String)` → `UserEntity name(String)`),
**链式调用跨接口不中断**。不满足约束(多类型参数或实参非自身)时编译报错。

### 只读列与 default 默认值

**不写 setter = 该列不由用户维护**;`@Column.write()` 显式控制列参与 INSERT/UPDATE 的组合:

| 列形态 | INSERT | UPDATE SET | 语义 |
|---|---|---|---|
| 抽象 getter(无 setter 无 default) | 排除 | 排除 | 数据库维护 |
| `default` getter + `BOTH`(默认) | 自动调用 default 绑定 | 自动调用 default 刷新 | 框架自动维护 |
| `default` getter + `INSERT_ONLY` | 自动调用 default 绑定 | 排除 | 只插入一次(如 `created_at`) |
| `default` getter + `UPDATE_ONLY` | 排除 | 自动调用 default 刷新 | 只更新(如 `last_seen_at`) |
| 任何形态 + `NONE` | 排除 | 排除 | 数据库维护 |

`default` 方法是"默认值来源"——参与 INSERT/UPDATE 时框架自动调用(经生成的
`接口.super.getter()` 桥接,不受 impl 覆盖的 getter 影响)取值绑定 SQL:

```java
@Column(name = "inserted_at", write = WritePolicy.INSERT_ONLY)
default LocalDateTime insertedAt() {    // save 填一次,update 不触碰
    return LocalDateTime.now();
}

@Column(name = "updated_at")
default LocalDateTime updatedAt() {     // 默认 BOTH:save 与 update 都自动刷新
    return LocalDateTime.now();
}
```

无 `@Column`/`@Id` 注解的 default 方法仍是辅助逻辑(如 `default String displayName()`),不参与映射。

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

### 2.1 自定义类型转换(`@Convert`)

标在 getter 上,用自定义转换器处理 Java 类型 ↔ 数据库类型的转换(如把 `UUID`/`LocalDate`
存为 VARCHAR 文本、枚举存为字符串等):

```java
// 转换器:实现 JForgeConverter<X>(X = 实体字段类型),公开无参构造器;
// 两个方向都以 Object 传递数据库侧值——适配任意数据库类型;
// toDatabase/toEntity 必须接受并透传 null;
// sqlType() 可选覆盖——默认 JDBCType.OTHER(unknown,由数据库按目标列推断);
// 需钉死绑定 SQL 类型时返回 JDBCType.VARCHAR 等(或驱动自定义 SQLType 如 PGType)。
public final class UuidStringConverter implements JForgeConverter<UUID> {
    @Override public Object toDatabase(UUID attribute) { return attribute == null ? null : attribute.toString(); }
    @Override public UUID toEntity(Object dbData) { return dbData == null ? null : UUID.fromString((String) dbData); }
    @Override public SQLType sqlType() { return JDBCType.VARCHAR; }   // 可选
}

// 实体:getter 标 @Convert,处理器编译期把转换调用生成进 JDBC 代码。
public interface User {
    @Convert(converter = UuidStringConverter.class)
    UUID externalId();
    User externalId(UUID externalId);
}
```

- 绑定:`ps.setObject(i, CONVERTER_X.toDatabase(entity.getter()), CONVERTER_X.sqlType())`——
  SQL 类型由转换器 `sqlType()` 决定,默认 `JDBCType.OTHER`(unknown)由数据库按目标列推断
  (jsonb 等不接受 varchar 隐式转换的类型也能绑定);返回 DB 原生对象(如 jsonb → `PGobject`)
  的转换器保持默认 `OTHER` 最稳妥,输出确定作为某具体类型发送时才覆盖 `sqlType()`;
  读取:`setter(CONVERTER_X.toEntity(rs.getObject(i)))`——裸 `getObject` 取驱动的默认表示
  (如 PG jsonb → `PGobject`),转换器内部强转——**任意数据库类型均可转换**;
  转换器以 `private static final` 字段嵌入生成的仓库 impl,运行时零反射
- 转换器类可与实体同批编译(处理器经 `MirroredTypeException` 读取类型镜像)或预编译
- 转换列的 null 透传给转换器,`nullable`/枚举判定不参与;列名仍按 `@Column`/命名策略

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

**@Bind 绑定转换器**(按转换列查询):
`@Bind(value = "d", converter = XxxConverter.class)` 把该参数的绑定从"按声明类型选 `setXxx`"
改为 `ps.setObject(i, CONV.toDatabase(param), CONV.sqlType())`——参数与实体列经同一转换器
生成相同存储表示才能命中(实体列存转换后文本,如 `LocalDate` → VARCHAR,查询参数必须同样
转成文本):

```java
@Query("SELECT * FROM convert_users WHERE birth_date = :d")
ConvertUser findByBirthDate(@Bind(value = "d", converter = StringDateConverter.class) LocalDate d);
```

- 转换器可为同批源码或预编译(与实体列 `@Convert` 同一机制);转换器以 `static final` 字段嵌入
- 动态 WHERE 下同样生效(运行时索引绑定);`@Nullable` 参数传 `null` 时整段移除(条件跳过)

**@Query 动态 WHERE**(JSpecify `@Nullable` 驱动,两种语法):

```java
/** ① 方括号 [ ] 显式标记动态段(段内参数必须标 @Nullable,否则编译报错) */
@Query("SELECT id, user_name, age FROM users WHERE [age = :age] AND user_name = :name")
List<UserEntity> findDynamicByAgeAndName(@Bind("age") @Nullable Integer age, @Bind("name") String name);

/** ② @Nullable 自动推断:恰好一个占位符的片段,参数标 @Nullable 即动态 */
@Query("SELECT id, user_name, age FROM users WHERE user_name = :name AND age = :age")
List<UserEntity> findAutoDynamicByAgeAndName(@Bind("name") String name, @Bind("age") @Nullable Integer age);
```

- 动态段运行时参数为 `null` 时**整段移除**,非 null 时拼接——连接符(`AND`/`OR`)由生成器维护,首个实际执行的条件得 `WHERE`、其后按用户写的连接符,动态段跳过时连接符随之消失
- 含动态段的 @Query 运行时拼接 SQL(不生成常量字段);纯静态 @Query 仍是 SQL 常量 + 静态绑定(零开销)
- 多占位符片段不做自动推断(需方括号显式);方括号段强制"恰好一个占位符 + 参数标 `@Nullable`"

**@Query + @Condition 追加条件**(手写 SQL 之外自动追加条件):

```java
/** 动态追加:age 非 null 时 AND age > ?(null 时仅按 name 查) */
@Query("SELECT id, user_name, age FROM users WHERE user_name = :name")
List<UserEntity> find(@Bind("name") String name, @Condition(op = Op.GT) @Nullable Integer age);

/** 静态追加:恒拼接(无 @Nullable),SQL 常量 + 静态绑定,零开销 */
@Query("SELECT id, user_name, age FROM users WHERE user_name = :name")
List<UserEntity> find(@Bind("name") String name, @Condition(value = "age", op = Op.GE) int minAge);
```

- 标注 `@Condition` 的参数(可同时标 `@Bind`)自动追加为条件:`字段 = @Condition.value`(缺省按参数名) → 实体列,`op` 指定操作符;追加条件用 `AND` 连接(无 WHERE 时补 `WHERE`)
- `@Nullable` 决定动态性:标了则 null 时跳过(运行时拼接),未标则恒拼接(进入 SQL 常量)

### @Select 声明式查询（不写 SQL）

`@Select` 方法不用写 SQL——处理器按返回类型与方法参数自动构造 SELECT:

```java
@Select
List<UserEntity> findByName(String name);                          // WHERE user_name = ?

@Select
List<UserEntity> findByAge(@Nullable Integer age);                 // age 为 null 时跳过条件

@Select
List<UserEntity> findOlderThan(@Condition(op = Op.GT) Integer age);    // 操作符:age > ?

@Select
long countByName(String name);                                     // SELECT COUNT(*)

@Select
List<UserNameDto> findNameDtoById(Long id);                        // record 投影
```

**规则**:

- **返回类型**决定 SELECT 列:实体/{@code List<实体>} → 全列(FROM 宿主表,只能返回宿主实体);record → 组件列(组件名经命名策略);标量(`long`/`int`/`boolean`) → `COUNT(*)`
- **参数即条件**,默认等于(`col = ?`);`@Condition(value = "字段名", op = Op.X)` 可指定字段(缺省按参数名)与操作符(`EQ/NE/GT/LT/GE/LE/LIKE/NOT_LIKE`);字段必须匹配实体字段或 record 组件,否则编译报错
- **条件参数自动复用列转换器**:条件字段映射到实体列、且该列标了 `@Convert` 时,绑定自动经
  `ps.setObject(i, CONV.toDatabase(param), CONV.sqlType())`(无需注解)——条件值须与列存
  相同的转换后表示才能命中;`@Update` 的 `@UpdateSet` SET 值同样自动经列转换器写库
- **JSpecify `@Nullable` 参数动态拼接**(`org.jspecify.annotations.Nullable`,经 jforge-annotation 传递依赖提供):运行时为 `null` 时跳过该条件(MyBatis 风格动态 WHERE);未标注的参数始终拼接
- **生成形态自动选择**:方法不含任何 `@Nullable` 参数时,编译期拼出完整 SQL 常量 + 静态索引绑定(与手写 JDBC 等价);含动态参数才生成运行时拼接(where 前缀变量 + 条件 if 块)
- 与 `@Query` 互斥(同一方法只能标一个)

### 条件对象（`@Where` 复杂 WHERE）

`@Where` 参数是**条件对象**——处理器递归展开其字段为 WHERE 条件,支持分组括号与 `OR` 连接:

```java
public class UserCriteria {
    String name;                                     // user_name = ?（null 跳过）
    @Or @Condition(op = Op.GT) Integer age;          // OR age > ?
    @Condition(value = "name") Optional<String> nickname;  // IS NULL（空）/ = ?（有值）
    AddressCriteria address;                         // AND (city = ? AND street = ?)
}

@Select
List<UserEntity> findComplex(@Where UserCriteria criteria);
// → WHERE user_name = ? OR age > ? AND (city = ? AND street = ?)（按运行时字段值动态拼装）
```

- **值类型字段**（基本/包装/`String`/日期/枚举…）→ 单条件 `列 op ?`;字段名经命名策略映射列,`@Condition` 可指定字段与操作符;字段值为 `null` 时跳过
- **自定义类字段**（非 JDK 值类型）→ **括号分组** `( ... )` 递归展开,为 `null` 时整个括号跳过
- **连接符**:字段上的 `@And`/`@Or` 定义与上一条件的连接(缺省 `AND`)
- **`Optional` 三族**（`Optional`/`OptionalInt`/`OptionalLong`）:值为空 → `列 IS NULL`(显式空值查询);有值 → `列 op ?`;`Optional` 本身为 `null` → 跳过
- 条件对象字段的读取方法:getter 惯例(`getName()`)、record accessor(`name()`)或 `isXxx()`;列映射失败编译报错

### 声明式更新与删除（`@Update`/`@Delete`）

与 `@Select` 对称的不写 SQL 写操作——`@UpdateSet` 定义 SET 列,WHERE 条件复用同一套机制:

```java
@Update
int updateNameAndAge(@UpdateSet String name, @UpdateSet @Nullable Integer age, @Condition Long id);
// → UPDATE users SET user_name = ? , age = ?（age 为 null 时跳过该 SET）WHERE id = ?

@Update
int updateNickname(@UpdateSet(value = "name") Optional<String> nickname, @Condition Long id);
// → Optional 空时 SET user_name = NULL（显式置空）

@Update
int updateByCriteria(@UpdateSet String name, @Where UserCriteria criteria);   // 条件对象 WHERE

@Delete
int deleteByCriteria(@Where UserCriteria criteria);   // DELETE FROM users WHERE ...
```

- **`@UpdateSet`**(参数):SET 列——`@Nullable` 值为 `null` 时跳过该 SET(保持原值);`Optional` 空 → `SET 列 = NULL`、有值 → `SET 列 = ?`
- **WHERE**:`@Condition` 参数 / `@Where` 条件对象——动态语义(`@Nullable` 跳过、`Optional` IS NULL、括号分组)与 `@Select` 完全一致
- **返回**:影响行数(`int`/`long`/`boolean`);全静态 → SQL 常量,含动态 → 运行时拼接
- 方法间互斥(`@Select`/`@Query`/`@Update`/`@Delete` 同方法只能标一个);方法名避开 `BaseRepository` 继承的 CRUD 方法名(save/update/delete/findById…)

### rawSql 原生 SQL 片段

`@Condition`/`@UpdateSet` 的 `rawSql` 属性直接使用原生 SQL 片段(替代"字段 + 操作符"拼装),覆盖 `@Select`/`@Update`/`@Delete` 的参数条件与 `@Where` 条件对象字段:

```java
/** 常量条件:WHERE age > 20(参数不绑定,仅用于 @Nullable/Optional 的跳过控制) */
@Select
List<UserEntity> findOlderThanRaw(@Condition(rawSql = "age > 20") Integer ignored);

/** SET 表达式:score = score + ?(片段中的 ? 绑定参数) */
@Update
int incrementScore(@UpdateSet(rawSql = "score = score + ?") Integer increment, @Condition Long id);

/** 条件对象字段:非 null 时拼 age > 18 */
public class UserCriteria {
    @Condition(rawSql = "age > 18")
    public Integer adult;
}
```

- **`?` 占位符**:rawSql 含 `?` → 绑定该参数/字段值;无 `?` → 纯常量片段(参数不绑定,仅作动态跳过开关)
- **动态语义保留**:`@Nullable` 参数 null 时跳过;`Optional` 有值时拼 rawSql(空时跳过——rawSql 不生成 `IS NULL`);条件对象字段 null 时跳过
- 条件对象字段的 `@Condition(rawSql)` 不支持 `Optional` 类型(编译报错);全静态时进 SQL 常量、含动态走运行时拼接

## 4. 配置

`@JForgeConfig` 可放在**包**(`package-info.java`)或**接口**(实体/仓库接口)上,两层解析:

1. **接口自身标注优先**:接口上直接标了 `@JForgeConfig` → 用接口自己的
2. **包链继承**:否则沿包链向上(所在包 → 父包 → …)找最近的包级配置——放在公共父包可管理全部子包

都没有 → 用默认值(配置不做合并、不冲突检测):

```java
@JForgeConfig(
    dialect = Dialect.POSTGRESQL,
    naming = NamingStrategy.CAMEL_TO_SNAKE,   // userName → user_name
    implSuffix = "Impl",
    springBeans = true,
    logSql = false,
    batchSize = 100
)
package com.example.data;
```

| 属性 | 默认 | 说明 |
|---|---|---|
| `dialect` | POSTGRESQL | SQL 方言：POSTGRESQL（真 PG，生成键走 `INSERT ... RETURNING`）/ MYSQL / SQLITE / H2（2.3 不支持 RETURNING，走 JDBC 标准）——**H2 测试/应用必须显式标 `Dialect.H2`**，`POSTGRESQL` 现仅用于真 PG |
| `naming` | NONE | 列名推断策略(无 @Column 时) |
| `tableNaming` | CAMEL_TO_SNAKE | 表名推断策略(无 @Table 或 name 为空时)；`NONE` = 实体接口名原样 |
| `implSuffix` | `_Impl` | 生成类后缀(仓库 impl 及其嵌套的实体 impl) |
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
