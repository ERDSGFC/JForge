# ORM 开发计划

**性能优先**的微型 ORM（`orm` 模块）：
- 目标 1：与裸 JDBC 一样快（框架开销 <5%）— ✅ **已达成**
- 目标 2：兼容 AOT（GraalVM Native Image 可构建、可运行）

## 架构：Repository 接口继承 + 编译期生成实现

```
用户代码                              Processor 自动生成                    orm 框架库
────────                             ────────────────                    ──────────
@Dao                                UserRepository_Impl                  BaseRepository<T,ID>
public interface UserRepository      implements UserRepository               ↓
    extends BaseRepository<U,Long>       ↓                              @Dao/@Query/@Bind
{                                     直接 JDBC 实现                    注解层
    @Query(...)                        → new UserEntity_Impl()
    List<User> findByAge(int age);       rs.getLong(1)
}                                       ps.setString(2,...)

UserEntity (interface)              UserEntity_Impl
```

**核心思想**：用户定义 `@Table` 实体接口 + `@Dao` 仓库接口继承 `BaseRepository`，processor 在编译期生成**直写 JDBC 的实现类**——运行时零反射、零元数据查找、零动态分发。

## 模块结构

```
orm-annotation/      # 注解：@Table/@Id/@Column/@GeneratedValue/@Transient（METHOD 作用域）
                     #       @Dao/@Query/@Bind/@ReturnGeneratedKeys
orm-processor/       # 编译期生成器（javapoet + auto-service，provided，不进运行时）
  ├── EntityProcessor.java      # @Table 接口 → UserEntity_Impl（字段 + getter + builder setter）
  ├── RepositoryProcessor.java  # @Dao 接口 → UserRepository_Impl（CRUD 12 方法 + @Query）
  ├── EntityModel.java          # 共享实体模型解析
  ├── SqlCodegen.java           # 绑定/读取代码块生成（编译期类型决策）
  └── TypeNameUtils.java        # 类型 → javapoet TypeName / JDBC getter/setter 映射
orm/                 # 框架库：BaseRepository（用户文件）、OrmException
orm-bench/           # ORM vs 裸 JDBC 基准
```

## 用法示例

```java
// 1. 实体（接口 + builder setter）
@Table(name = "users")
public interface UserEntity {
    @Id @GeneratedValue Long id();
    UserEntity id(Long id);
    @Column(name = "user_name") String name();
    UserEntity name(String name);
    Integer age();
    UserEntity age(Integer age);
}

// 2. 仓库（继承 BaseRepository 获得 CRUD；@Query 自定义查询）
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);   // record DTO 投影
}

// 3. 使用（Repositories 工厂编译期生成）
UserRepository repo = Repositories.createUserRepository(dataSource);
UserEntity user = repo.save(new UserEntity_Impl().name("qin").age(25));
UserEntity found = repo.findById(1L);
repo.update(found);
repo.deleteById(1L);
```

## 基准结果（ORM vs 裸 JDBC，`-f 3 -i 5`，15 次测量）

| 操作 | ORM（生成代码） | 裸 JDBC | ORM/JDBC | 状态 |
|---|---:|---:|---:|---:|
| Insert | 529,893 | 512,190 | **+3.5%** | ✅ |
| FindById | 1,558,908 | 1,560,231 | **-0.1%** | ✅ 持平 |
| FindAll | 1,658,211 | 1,620,312 | **+2.3%** | ✅ |

**结论**：编译期生成直写 JDBC = 手写 JDBC 性能（甚至略优）。目标 1 达成。

## 阶段状态

- ✅ **Repository 架构重构**（原 Phase 1/1.5 全部替换）：实体接口 + @Dao 仓库 + 编译期生成（CRUD 12 方法 + @Query + DTO 投影 + Repositories 工厂）
- ✅ **基准验证**：ORM vs 裸 JDBC 全部达标
- ⬜ **Phase 3 关联映射**：实体是接口 → 懒加载用 `java.lang.reflect.Proxy`（无需字节码库）
- ⬜ **Phase 4 缓存层**：L1 按主键缓存，写操作失效
- ⬜ **Phase 6 GraalVM 验证**：native-image 构建 + native 下测试（生成代码零反射，预期直接通过）

## 性能设计原则（源自本项目 JMH 基准结论）

1. 句柄/调用点必须可被 JIT 内联：`invokeExact` > `invoke`（慢 28-73%）
2. `static final` 引用（instance 字段慢 17-84%）
3. 类型决策编译期完成（精确 setter/getter，避免 `setObject`/`findColumn`）
4. PreparedStatement 复用（statement cache）
5. 生成代码是直写 JDBC —— 与手写等价是性能上限

## AOT 状态

- 生成代码：纯静态方法调用、直接构造、无反射、无 MethodHandle、无 Class.forName → Native Image 友好
- 待 Phase 6 实测验证
