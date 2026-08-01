# ORM 开发计划

**性能优先**的微型 ORM（`orm` 模块）：
- 目标 1：与裸 JDBC 一样快（框架开销 <5%）
- 目标 2：**兼容 AOT**（GraalVM Native Image 可构建、可运行）

## 定位与选型

| 项 | 决策 |
|---|---|
| 定位 | 性能优先微型 ORM，JVM 与 GraalVM Native Image 双模式 |
| 数据库 | H2（内嵌），**PostgreSQL 兼容模式**（`MODE=PostgreSQL`） |
| 连接池 | HikariCP |
| Java | 25 |
| 实体形态 | 纯 POJO + 注解驱动 |
| SQL 安全 | 全部 `PreparedStatement` 参数化，防注入 |

## 性能设计原则（源自本项目 JMH 基准结论）

> 本项目对 JVM 调用机制做了 12 轮 JMH 基准，以下原则直接来自实证结论（见 `BENCHMARK_RESULTS.md`）：

1. **字段读写用 `MethodHandle.invokeExact`** — `invoke` 的 asType 适配开销使调用慢 28-73%（Run 10），`invokeExact` 追平直接调用。
2. **句柄必须 `static final`** — instance 字段句柄实测慢 17-84%（Run 12，反射 -56%、MethodHandle -84%）。JIT 常量折叠后调用点深度内联。
3. **SQL 只生成一次** — `EntityMetadata` 解析时预生成并缓存全部 SQL 字符串，运行时零拼接。
4. **参数绑定按类型用精确 setter** — `ps.setLong/setString` 而非 `setObject`。
5. **ResultSet 按类型用精确 getter** — `rs.getLong/getString` 而非 `getObject(col, type)`。
6. **PreparedStatement 复用** — 开启 HikariCP statement cache。
7. **连接池** — HikariCP。
8. **测量遵循 JMH 标准写法** — 单次调用 + 返回对象防 DCE（手写循环扭曲结论，见 Run 8）。

## AOT 设计原则（GraalVM Native Image）

> Native Image 默认**不支持运行时反射、动态代理、运行时 MethodHandle 创建**（需显式配置且损耗性能）。因此：

1. **运行时零反射** — 映射元数据在**编译期**生成（annotation processor 扫描 `@Table` 实体），生成可直接调用的访问器代码（调用实体的 getter/setter，Native Image 完全支持）。
2. **运行时零 MethodHandle 创建** — 句柄仅在 JVM 模式（回退路径）使用；AOT 模式走编译期生成的访问器类。
3. **无字节码库**（CGLIB/ByteBuddy）— 本就不引入；懒加载代理在 AOT 下用编译期生成的实现类替代 JDK Proxy。
4. **配置可追溯** — 仍需的资源（驱动、连接池）按 GraalVM 惯例在 `build.gradle`/`META-INF/native-image` 注册，保持最小化。

## 模块结构

```
orm-processor/                      # ✅ 构建期元数据生成器（零依赖，provided，不进入运行时）
orm/
├── pom.xml                          # 依赖: H2, HikariCP, JUnit5(test), orm-processor(provided)
└── src/
    ├── main/java/com/qin/orm/
    │   ├── OrmException.java
    │   ├── Session.java             # 顶层 API：CRUD + 事务
    │   ├── SessionFactory.java
    │   ├── annotation/              # Table / Id / Column / GeneratedValue / Transient
    │   ├── core/
    │   │   ├── EntityMetadata.java  # 元信息：优先加载生成类，回退反射（JVM 模式）
    │   │   ├── FieldAccessor.java   # get/set 访问器接口（生成实现 + 反射实现）
    │   │   ├── SqlGenerator.java    # SQL 生成（仅元数据解析时调用一次）
    │   │   ├── RowMapper.java
    │   │   └── DefaultRowMapper.java# 访问器赋值 + 按类型 getter
    │   ├── query/                   # [Phase 2] 流式查询 DSL
    │   ├── relation/                # [Phase 3] 关联映射（AOT：生成实现类替代代理）
    │   ├── cache/                   # [Phase 4] 缓存层
    │   └── benchmark/               # [Phase 5] JMH：ORM vs 裸 JDBC
    └── test/java/com/qin/orm/
        ├── UserEntity.java
        └── SessionCrudTest.java     # H2 PG 模式集成测试
```

## 阶段计划

### ✅ Phase 1：核心引擎（CRUD）— 已完成，待按性能原则优化

**已完成**：5 个映射注解、`EntityMetadata`（反射解析一次 + 缓存）、`Session` CRUD + 事务、`SessionFactory`、6/6 集成测试通过。

**性能优化已完成**（6/6 测试通过）：
- ✅ SQL 预生成缓存：`EntityMetadata` 解析时生成全部 5 条 SQL 字符串，运行时零拼接（`SqlGenerator` 参数化，仅解析时调用一次）
- ✅ 参数绑定精确 setter：按字段类型解析时确定 `ParamBinder`（`setLong/setString/...`，兜底 `setObject`），`meta.bindInsertParams/bindUpdateParams/bindId` 统一出口
- ✅ ResultSet 精确 getter：按字段类型解析时确定 `RowReader`（`getLong` + `wasNull` 处理，兜底 `getObject`），`DefaultRowMapper` 直接消费
- ✅ HikariCP statement cache：`cachePrepStmts=true`、`prepStmtCacheSize=250`、`prepStmtCacheSqlLimit=2048`（预生成的固定 SQL 使每条语句都命中缓存）

**API 示例**：
```java
Session session = SessionFactory.open("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
session.insert(user);                     // @GeneratedValue 自增 id 自动回填
UserEntity u = session.findById(UserEntity.class, 1L);
session.update(u);
session.delete(u);
```

### ✅ Phase 1.5：构建期元数据生成（AOT 就绪）— 已完成

**架构**：
- 新增 `orm-processor` 子模块（javax.annotation.processing，**零依赖**——用 Mirror API 按限定名匹配注解，避免与 orm 循环依赖；`provided` scope，不进入运行时 classpath）
- 编译期扫描 `@Table` 实体，为每个实体生成 `Xxx_Metadata`（`com.qin.orm.generated` 包）：
  - `entityClass()`、表名、**预生成全部 5 条 SQL 字符串**
  - `FieldAccessor` 列表：直接调用实体 getter/setter 的 lambda（编译期 invokedynamic，Native Image 安全）
- 同时生成 `GeneratedMetadataRegistry`（**静态引用**各实体 `.class` 与 `INSTANCE`——无 `Class.forName`，AOT 可达性分析直接保留）
- `EntityMetadata` 双路径：`GeneratedMetadataRegistry.find()` 命中 → 生成路径；未命中 → 反射回退（JVM）
- **编译期报错**：实体缺 getter/setter、缺 @Id 等映射错误在 `mvn compile` 直接失败

**验证**：10/10 测试通过（6 CRUD 走生成路径 + 4 元数据路径验证：registry 命中、SQL 与反射回退一致、未注册类回退反射）。

**已知限制**：registry 在首个处理轮生成一次；增量编译中后续轮新出现的实体不会追加进 registry（全量 Maven 构建无此问题）。

### ⬜ Phase 2：流式查询 DSL

`com.qin.orm.query`：`Condition`（EQ/NE/GT/GTE/LT/LTE/LIKE/IN/IS_NULL/IS_NOT_NULL）、`Order`（ASC/DESC）、`Query<T>`（不可变链式构建器）。

```java
List<UserEntity> users = session.query(UserEntity.class)
    .where("age", Condition.Operator.GT, 18)
    .orderBy("age", Order.Direction.DESC)
    .limit(10).offset(0)
    .list();
```

性能要求：条件 SQL 首次构建时组装一次并缓存；列名校验防注入；`LIMIT ? OFFSET ?`（PostgreSQL 语法）。

### ⬜ Phase 3：关联映射

`@OneToOne` / `@OneToMany` / `@ManyToOne` / `@JoinColumn`；默认懒加载。**AOT 方案**：懒加载实现类由 annotation processor 编译期生成（非 JDK Proxy，Proxy 在 Native Image 需配置且有限制）；`fetch=EAGER` 急加载；仅外键关联。

### ⬜ Phase 4：缓存层

`Cache<K,V>`、`LruCache`（线程安全 LRU，默认 1000）、`CacheManager`、`@Cacheable(ttlSeconds)`；L1 按主键缓存；写操作自动失效。纯 Java 实现，AOT 天然兼容。

### ⬜ Phase 5：JMH 性能基准（目标验收）

新增 `com.qin.orm.benchmark`，JMH 对比 **ORM vs 裸 JDBC**（同表、同操作、同连接池）：
- insert（含自增回填）、findById、update、delete、findAll
- 测量遵守标准写法（单次调用 + 返回对象防 DCE）
- **验收标准：ORM/裸 JDBC ≤ 1.05（框架开销 <5%）**

### ⬜ Phase 6：GraalVM Native Image 验证（AOT 验收）

- 用 GraalVM 构建 native image（`native-image` 命令行或 Maven 插件）
- 验证：native 镜像下 CRUD 集成测试通过、Phase 1.5 生成类被使用、无反射/MethodHandle 运行期错误
- 记录需要的 native-image 配置（驱动注册、资源）

## 关键设计决策（已完成部分）

1. **连接所有权**：`Session.ownsDataSource` 标记池归属——Session 自建的池由 `close()` 关闭，外部传入的池由调用方管理
2. **事务连接管理**：事务内操作复用单一连接且不被关闭（`withConnection` helper），提交/回滚后才归还
3. **`EntityMetadata` 缓存**：`ConcurrentHashMap` 按类缓存
4. **H2 内存库**：`DB_CLOSE_DELAY=-1` 保持库在 JVM 存活期间存在
5. **双模式元数据**：AOT 优先编译期生成类，JVM 回退反射（保证两模式都可用，AOT 模式无反射）

## 验证方式

- 每个 Phase 完成后 `mvn test -pl orm` 集成测试通过
- 测试库：内存 H2 + PostgreSQL 兼容模式（`BIGSERIAL` 自增等 PostgreSQL 方言）
- **Phase 5 验收**：JMH 基准 ORM vs 裸 JDBC，框架开销 <5%
- **Phase 6 验收**：GraalVM native image 构建成功 + native 下测试通过
