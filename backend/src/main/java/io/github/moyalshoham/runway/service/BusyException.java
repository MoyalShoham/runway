package io.github.moyalshoham.runway.service;

/** The server is already running as many simulations as it allows; try again shortly. */
public class BusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusyException(String message) {
        super(message);
    }
}
