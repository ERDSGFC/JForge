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

    /** Inserts the entity (writing back a generated id) and returns it. */
    T save(T entity);

    /** Inserts all entities and returns the same list (ids written back). */
    List<T> save(List<T> entities);

    /** Deletes the row mapped by the entity. Returns whether a row was deleted. */
    boolean delete(T entity);

    /** Deletes the rows mapped by the entities. Returns the number of deleted rows. */
    int delete(List<T> entities);

    /** Deletes the row with the given id. Returns whether a row was deleted. */
    boolean deleteById(ID id);

    /** Deletes the rows with the given ids. Returns the number of deleted rows. */
    int deleteByIds(List<ID> ids);

    /** Updates all mapped columns of the entity, matched by its id. Returns whether a row was updated. */
    boolean update(T entity);

    /** Loads the entity by its id, or {@code null} if absent. */
    T findById(ID id);

    /** Loads the entities with the given ids (absent ids are skipped). */
    List<T> findByIds(List<ID> ids);

    /** Loads all rows of the entity's table. */
    List<T> findAll();

    /** Returns the total number of rows in the entity's table. */
    long count();

    /**
     * Returns whether a row with the given id exists.
     * @param id
     * @return
     */
    boolean existsById(ID id);
}
