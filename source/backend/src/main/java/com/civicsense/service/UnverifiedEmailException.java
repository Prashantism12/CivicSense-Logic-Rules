package com.civicsense.service;

/** Thrown at login when the account's e-mail has not been verified yet. */
public class UnverifiedEmailException extends RuntimeException {
    private final String email;
    public UnverifiedEmailException(String email) {
        super("Please verify your e-mail first. We have sent a new code.");
        this.email = email;
    }
    public String getEmail() { return email; }
}
