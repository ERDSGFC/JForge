package com.qin.orm.core;

import java.util.List;

/**
 * Base repository contract for an entity {@code T} with primary key {@code ID}.
 *
 * <p>User repositories extend this interface (e.g. {@code interface UserRepository
 * extends BaseRepository<UserEntity, Long>}) and are implemented at compile time by
 * the annotation processor: a concrete {@code XxxImpl} class is generated for each
 * {@code @Dao} interface, inheriting the CRUD behaviour below and adding derived
 * query methods declared on the interface (e.g. {@code findByAgeGreaterThan}).</p>
 *
 * @param <T>  the entity type
 * @param <ID> the primary-key type
 */
public interface BaseRepository<T, ID> {

    /**
     * Inserts the entity and writes back a generated id into it.
     *
     * @param entity the entity to persist (its {@code @Id} field is updated when generated)
     * @return the same entity instance, now carrying its id
     */
    T save(T entity);

    /**
     * Inserts all entities, writing back generated ids.
     *
     * @param entities the entities to persist
     * @return the same list instance, each entity carrying its id
     */
    List<T> save(List<T> entities);

    /**
     * Deletes the row mapped by the entity's id.
     *
     * @param entity the entity to delete
     * @return {@code true} if a row was deleted
     */
    boolean delete(T entity);

    /**
     * Deletes the rows mapped by the entities' ids.
     *
     * @param entities the entities to delete
     * @return the number of deleted rows
     */
    int delete(List<T> entities);

    /**
     * Deletes the row with the given id.
     *
     * @param id the primary key of the row to delete
     * @return {@code true} if a row was deleted
     */
    boolean deleteById(ID id);

    /**
     * Deletes the rows with the given ids.
     *
     * @param ids the primary keys of the rows to delete
     * @return the number of deleted rows
     */
    int deleteByIds(List<ID> ids);

    /**
     * Updates all mapped columns of the entity, matched by its id.
     *
     * @param entity the entity carrying the new values and the id to match
     * @return {@code true} if a row was updated
     */
    boolean update(T entity);

    /**
     * Loads the entity by its id.
     *
     * @param id the primary key
     * @return the entity, or {@code null} if absent
     */
    T findById(ID id);

    /**
     * Loads the entities with the given ids.
     *
     * @param ids the primary keys
     * @return the matching entities in an unspecified order (absent ids are skipped)
     */
    List<T> findByIds(List<ID> ids);

    /**
     * Loads all rows of the entity's table.
     *
     * @return all entities
     */
    List<T> findAll();

    /**
     * Returns the total number of rows in the entity's table.
     *
     * @return the row count
     */
    long count();

    /**
     * Returns whether a row with the given id exists.
     *
     * @param id the primary key
     * @return {@code true} if a row with the id exists
     */
    boolean existsById(ID id);
}
