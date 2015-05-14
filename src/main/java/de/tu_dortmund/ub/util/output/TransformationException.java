package de.tu_dortmund.ub.util.output;

/**
 * Created by cihabe on 06.05.2015.
 */
public class TransformationException extends Exception {

    public TransformationException() {
    }

    public TransformationException(String message) {
        super(message);
    }

    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}
