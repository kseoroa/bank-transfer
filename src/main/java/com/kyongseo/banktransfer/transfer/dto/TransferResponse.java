package com.kyongseo.banktransfer.transfer.dto;

public record TransferResponse(
        String fromAccountNumber,
        String toAccountNumber,
        long amount,
        long fromBalanceAfter
) {
}
