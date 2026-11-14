package com.pricingsim.service;

/** A requested resource (SKU, decision, run, cost record) does not exist; the API answers 404 ProblemDetail. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
