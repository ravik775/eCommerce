package org.bgm.userservice.exception;

public class DuplicateEmailException extends RuntimeException {
    // Deliberately doesn't include the email in the message: this
    // message reaches the HTTP response body verbatim (see
    // GlobalExceptionHandler#handleDuplicate), so embedding the email
    // here would both leak PII into API responses/any log or APM tool
    // that captures response bodies, and double as an account-
    // enumeration oracle (confirming a specific address is registered).
    public DuplicateEmailException() {
        super("A user with this email already exists");
    }
}
