package com.kyongseo.banktransfer.account;

import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 50)
    private String ownerName;

    @Column(nullable = false)
    private long balance;

    public Account(String accountNumber, String ownerName, long initialBalance) {
        if (initialBalance < 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.balance = initialBalance;
    }

    public void deposit(long amount) {
        validatePositive(amount);
        this.balance += amount;
    }

    public void withdraw(long amount) {
        validatePositive(amount);
        if (this.balance < amount) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;
    }

    private void validatePositive(long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
    }
}
