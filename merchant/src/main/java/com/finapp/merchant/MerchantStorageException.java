package com.finapp.merchant;

/** A storage failure in the merchant module — infrastructure, never a domain outcome. */
public class MerchantStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantStorageException(String message) {
        super(message);
    }
}
