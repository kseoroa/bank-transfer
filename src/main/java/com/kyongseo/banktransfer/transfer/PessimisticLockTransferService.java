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
     * 비관적 락(SELECT ... FOR UPDATE)으로 두 계좌를 잠근 뒤 이체한다.
     * 데드락 방지를 위해 이체 방향과 무관하게 항상 id가 작은 계좌부터 락을 건다.
     */
    @Transactional
    public TransferResponse transfer(String fromAccountNumber, String toAccountNumber, long amount) {
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        Long fromId = findAccountId(fromAccountNumber);
        Long toId = findAccountId(toAccountNumber);

        Account from;
        Account to;
        if (fromId < toId) {
            from = lockAccount(fromId);
            to = lockAccount(toId);
        } else {
            to = lockAccount(toId);
            from = lockAccount(fromId);
        }

        from.withdraw(amount);
        to.deposit(amount);

        return new TransferResponse(fromAccountNumber, toAccountNumber, amount, from.getBalance());
    }

    private Long findAccountId(String accountNumber) {
        return accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    private Account lockAccount(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
