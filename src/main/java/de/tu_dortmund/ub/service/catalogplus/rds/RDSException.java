package de.tu_dortmund.ub.service.catalogplus.rds;

/**
 * Created by cihabe on 08.03.2015.
 */
public class RDSException extends Exception {

    public RDSException() {
    }

    public RDSException(String message) {
        super(message);
    }

    public RDSException(String message, Throwable cause) {
        super(message, cause);
    }
}
