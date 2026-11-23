package com.kyongseo.banktransfer.transfer;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;

    /**
     * 1단계: 락 없이 "조회 → 메모리에서 잔액 변경 → 커밋 시 UPDATE" 흐름.
     * 동시 요청이 같은 잔액을 읽으면 lost update가 발생한다 (2단계에서 재현 예정).
     */
    @Transactional
    public TransferResponse transfer(String fromAccountNumber, String toAccountNumber, long amount) {
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        Account from = findAccount(fromAccountNumber);
        Account to = findAccount(toAccountNumber);

        from.withdraw(amount);
        to.deposit(amount);

        return new TransferResponse(fromAccountNumber, toAccountNumber, amount, from.getBalance());
    }

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
