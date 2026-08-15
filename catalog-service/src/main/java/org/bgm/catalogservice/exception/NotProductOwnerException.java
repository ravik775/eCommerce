package org.bgm.catalogservice.exception;

/** A PROVIDER tried to modify a product they don't own — ADMIN has no such restriction. */
public class NotProductOwnerException extends RuntimeException {
    public NotProductOwnerException(long productId) {
        super("Not the owner of product: " + productId);
    }
}
