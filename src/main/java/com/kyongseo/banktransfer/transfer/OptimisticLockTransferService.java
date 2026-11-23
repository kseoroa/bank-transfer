package com.kyongseo.banktransfer.transfer;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.idempotency.IdempotencyRecord;
import com.kyongseo.banktransfer.idempotency.IdempotencyService;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import com.kyongseo.banktransfer.transfer.dto.TransferResult;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 낙관적 락(@Version)으로 이체한다. 락 없이 읽고, 커밋 시 version이 바뀌었으면 충돌로 보고 재시도한다.
 *
 * <p>재시도마다 새 트랜잭션이 필요하므로 메서드에 @Transactional을 붙이지 않고 TransactionTemplate을 쓴다.
 * (같은 트랜잭션 안에서 재시도하면 오래된 엔티티가 영속성 컨텍스트에 남아 계속 충돌한다.)
 */
@Slf4j
@Service
public class OptimisticLockTransferService {

    private final AccountRepository accountRepository;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final TransferMetrics metrics;
    private final int maxAttempts;
    private final long backoffMillis;

    public OptimisticLockTransferService(AccountRepository accountRepository,
                                         IdempotencyService idempotencyService,
                                         TransactionTemplate transactionTemplate,
                                         TransferMetrics metrics,
                                         TransferProperties properties) {
        this.accountRepository = accountRepository;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.maxAttempts = properties.optimistic().maxAttempts();
        this.backoffMillis = properties.optimistic().backoffMillis();
    }

    public TransferResponse transfer(String fromAccountNumber, String toAccountNumber, long amount) {
        return transfer(fromAccountNumber, toAccountNumber, amount, null).response();
    }

    /**
     * @param idempotencyKey null이 아니면 시도마다 같은 트랜잭션에서 키 행을 잠그고, 이미 완료된 키면 이체 없이 저장된 결과를 반환한다.
     *                       version 충돌로 롤백되면 COMPLETED 기록도 함께 롤백되므로 재시도해도 안전하다.
     */
    public TransferResult transfer(String fromAccountNumber, String toAccountNumber, long amount,
                                   String idempotencyKey) {
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return transactionTemplate.execute(
                        status -> transferOnce(fromAccountNumber, toAccountNumber, amount, idempotencyKey));
            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    break;
                }
                metrics.recordOptimisticRetry();
                log.debug("낙관적 락 충돌, 재시도 {}/{}: {} -> {}", attempt, maxAttempts, fromAccountNumber, toAccountNumber);
                sleepWithJitter();
            }
        }

        metrics.recordOptimisticConflictFailure();
        log.warn("낙관적 락 재시도 {}회 모두 충돌: {} -> {}", maxAttempts, fromAccountNumber, toAccountNumber);
        throw new BusinessException(ErrorCode.TRANSFER_CONFLICT);
    }

    /** 한 번의 시도 = 하나의 트랜잭션. 커밋 시 version 불일치면 OptimisticLockingFailureException. */
    private TransferResult transferOnce(String fromAccountNumber, String toAccountNumber, long amount,
                                        String idempotencyKey) {
        IdempotencyRecord record = idempotencyService.lock(idempotencyKey, fromAccountNumber, toAccountNumber, amount);
        if (record != null && record.isCompleted()) {
            return TransferResult.replayed(record.toResponse());
        }

        Account from = findAccount(fromAccountNumber);
        Account to = findAccount(toAccountNumber);

        from.withdraw(amount);
        to.deposit(amount);

        TransferResponse response = new TransferResponse(fromAccountNumber, toAccountNumber, amount, from.getBalance());
        idempotencyService.complete(record, response);
        return TransferResult.executed(response);
    }

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    private void sleepWithJitter() {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis + ThreadLocalRandom.current().nextLong(backoffMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TRANSFER_CONFLICT);
        }
    }
}
