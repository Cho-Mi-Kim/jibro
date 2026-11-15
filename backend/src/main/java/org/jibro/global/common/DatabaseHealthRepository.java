package org.jibro.global.common;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseHealthRepository {
    private final JdbcTemplate db;

    public DatabaseHealthRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean isAvailable() {
        try {
            return Integer.valueOf(1).equals(db.queryForObject("SELECT 1", Integer.class));
        } catch (DataAccessException exception) {
            return false;
        }
    }
}
