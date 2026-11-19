package dev.chainbank.api.platform;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Minimal explicit-transaction helper for plain JDBC. */
@ApplicationScoped
public class Db {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    @Inject
    DataSource dataSource;

    /** Runs work in one transaction; commits on success, rolls back on any exception. */
    public <T> T tx(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    public static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && "23505".equals(s.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public static class DataAccessException extends RuntimeException {
        public DataAccessException(SQLException cause) {
            super(cause);
        }
    }
}
