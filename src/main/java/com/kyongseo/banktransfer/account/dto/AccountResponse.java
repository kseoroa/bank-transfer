package com.kyongseo.banktransfer.account.dto;

import com.kyongseo.banktransfer.account.Account;

public record AccountResponse(String accountNumber, String ownerName, long balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getAccountNumber(), account.getOwnerName(), account.getBalance());
    }
}
