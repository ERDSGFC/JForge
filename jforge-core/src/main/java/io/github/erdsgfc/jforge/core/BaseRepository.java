package io.github.erdsgfc.jforge.core;

import java.util.List;

/**
 * 实体 {@code T}（主键 {@code ID}）的仓库基础契约。
 *
 * <p>用户仓库继承该接口（如 {@code interface UserRepository extends BaseRepository<UserEntity,
 * Long>}），由注解处理器在编译期实现：为每个 {@code @Dao} 接口生成具体的 {@code XxxImpl} 类，
 * 继承下述 CRUD 行为并增加接口上声明的派生查询方法（如 {@code findByAgeGreaterThan}）。</p>
 *
 * <p>每个仓库还暴露 {@link TransactionOperations} 契约——编程式
 * {@code beginTransaction/commit/rollback} 与 {@code execute} 模板——使多语句工作可运行在
 * 单个事务内。</p>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface BaseRepository<T, ID> extends TransactionOperations {

    /**
     * 插入实体并回写生成的主键 id。
     *
     * @param entity 要持久化的实体（其 {@code @Id} 字段在生成时被更新）
     * @return 同一个实体实例，现在带上了它的 id
     */
    T save(T entity);

    /**
     * 批量插入所有实体并回写生成的主键 id。
     *
     * @param entities 要持久化的实体列表
     * @return 同一个列表实例，每个实体都带上了它的 id
     */
    List<T> save(List<T> entities);

    /**
     * 删除该实体 id 映射的行。
     *
     * @param entity 要删除的实体
     * @return 若删除了某行则返回 {@code true}
     */
    boolean delete(T entity);

    /**
     * 删除这些实体 id 映射的行。
     *
     * @param entities 要删除的实体列表
     * @return 被删除的行数
     */
    int delete(List<T> entities);

    /**
     * 删除指定 id 的行。
     *
     * @param id 要删除行的主键
     * @return 若删除了某行则返回 {@code true}
     */
    boolean deleteById(ID id);

    /**
     * 删除指定 id 列表的行。
     *
     * @param ids 要删除行的主键列表
     * @return 被删除的行数
     */
    int deleteByIds(List<ID> ids);

    /**
     * 按实体 id 匹配并更新其所有映射列。
     *
     * @param entity 携带新值与待匹配 id 的实体
     * @return 若更新了某行则返回 {@code true}
     */
    boolean update(T entity);

    /**
     * 按 id 加载实体。
     *
     * @param id 主键
     * @return 实体，若不存在则返回 {@code null}
     */
    T findById(ID id);

    /**
     * 按给定 id 列表加载实体。
     *
     * @param ids 主键列表
     * @return 匹配的实体（顺序不保证，不存在的 id 会被跳过）
     */
    List<T> findByIds(List<ID> ids);

    /**
     * 加载实体表的全部行。
     *
     * @return 所有实体
     */
    List<T> findAll();

    /**
     * 返回实体表的总行数。
     *
     * @return 行数
     */
    long count();

    /**
     * 返回指定 id 的行是否存在。
     *
     * @param id 主键
     * @return 若该 id 的行存在则返回 {@code true}
     */
    boolean existsById(ID id);

    /**
     * 创建一个所有字段均为默认值的全新空实体。当调用方无法（或不允许）直接引用生成的
     * 实现类时，可把它当作工厂使用。
     *
     * @return 类型为 {@code T} 的新实体实例
     */
    T createEntity();

}
