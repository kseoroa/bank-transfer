package com.kyongseo.banktransfer.account;

import com.kyongseo.banktransfer.account.dto.AccountResponse;
import com.kyongseo.banktransfer.account.dto.CreateAccountRequest;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int ACCOUNT_NUMBER_LENGTH = 12;

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        String accountNumber = generateUniqueAccountNumber();
        Account account = new Account(accountNumber, request.ownerName(), request.initialBalance());
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        return AccountResponse.from(account);
    }

    private String generateUniqueAccountNumber() {
        String accountNumber;
        do {
            accountNumber = randomDigits(ACCOUNT_NUMBER_LENGTH);
        } while (accountRepository.existsByAccountNumber(accountNumber));
        return accountNumber;
    }

    private String randomDigits(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
