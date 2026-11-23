package com.kyongseo.banktransfer.transfer.dto;

/**
 * @param replayed true면 이번 요청은 이체하지 않고, 같은 Idempotency-Key의 첫 결과를 돌려준 것
 */
public record TransferResult(TransferResponse response, boolean replayed) {

    public static TransferResult executed(TransferResponse response) {
        return new TransferResult(response, false);
    }

    public static TransferResult replayed(TransferResponse response) {
        return new TransferResult(response, true);
    }
}
