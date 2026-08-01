package com.qin.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionCrudTest {

    private HikariDataSource ds;
    private Session session;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        session = SessionFactory.open(ds);
    }

    @AfterEach
    void tearDown() {
        session.close();
        ds.close();
    }

    @Test
    void insertAndFindById() {
        UserEntity user = new UserEntity();
        user.setName("qin");
        user.setAge(25);

        session.insert(user);

        assertNotNull(user.getId(), "generated id should be written back");
        UserEntity found = session.findById(UserEntity.class, user.getId());
        assertNotNull(found);
        assertEquals("qin", found.getName());
        assertEquals(25, found.getAge());
    }

    @Test
    void update() {
        UserEntity user = new UserEntity();
        user.setName("qin");
        user.setAge(25);
        session.insert(user);

        user.setAge(26);
        session.update(user);

        UserEntity found = session.findById(UserEntity.class, user.getId());
        assertEquals(26, found.getAge());
        assertEquals("qin", found.getName());
    }

    @Test
    void findAll() {
        for (int i = 1; i <= 3; i++) {
            UserEntity user = new UserEntity();
            user.setName("user" + i);
            user.setAge(i);
            session.insert(user);
        }

        List<UserEntity> all = session.findAll(UserEntity.class);

        assertEquals(3, all.size());
    }

    @Test
    void delete() {
        UserEntity user = new UserEntity();
        user.setName("qin");
        user.setAge(25);
        session.insert(user);

        session.delete(user);

        assertNull(session.findById(UserEntity.class, user.getId()));
    }

    @Test
    void transactionCommit() {
        session.beginTransaction();
        UserEntity user = new UserEntity();
        user.setName("tx");
        user.setAge(1);
        session.insert(user);
        session.commit();

        assertNotNull(session.findById(UserEntity.class, user.getId()));
    }

    @Test
    void transactionRollback() {
        session.beginTransaction();
        UserEntity user = new UserEntity();
        user.setName("tx");
        user.setAge(1);
        session.insert(user);
        session.rollback();

        assertNull(session.findById(UserEntity.class, user.getId()));
    }
}
