package com.finapp.merchant;

/**
 * A merchant-bound capture cannot be composed (`P6-TSK-005`).
 *
 * <p><strong>Deliberately not an {@code ErrorCode}.</strong> Every other refusal in this module
 * answers a caller who can do something about it; this one is raised inside a capture's own
 * transaction, applied as the platform in response to a provider's answer, with no request to
 * refuse and nobody to tell. It fails the whole capture loudly — which is the point: the
 * alternative is posting fewer lines than the money owes and recording the capture as done.
 *
 * <p>Every message names <em>which</em> assumption broke, because each of them is supposed to
 * be unreachable and the useful information is which one was not.
 */
public class MerchantSettlementException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantSettlementException(String message) {
        super(message);
    }
}
