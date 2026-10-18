package com.example.tradestore.exception;

public class LowerVersionException extends RuntimeException {

    public LowerVersionException(String message) {
        super(message);
    }
}
