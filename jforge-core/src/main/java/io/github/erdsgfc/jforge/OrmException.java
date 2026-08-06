package io.github.erdsgfc.jforge;

/** Runtime exception thrown by the ORM for mapping/configuration errors. */
public class OrmException extends RuntimeException {

    public OrmException(String message) {
        super(message);
    }

    public OrmException(String message, Throwable cause) {
        super(message, cause);
    }
}
