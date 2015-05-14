package de.tu_dortmund.ub.service.catalogplus.vcs;

/**
 * Created by cihabe on 07.05.2015.
 */
public class VCSException extends Exception {

    public VCSException() {
    }

    public VCSException(String message) {
        super(message);
    }

    public VCSException(String message, Throwable cause) {
        super(message, cause);
    }
}
