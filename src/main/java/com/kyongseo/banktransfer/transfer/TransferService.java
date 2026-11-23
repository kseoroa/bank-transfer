package com.kyongseo.banktransfer.transfer;

import com.kyongseo.banktransfer.idempotency.IdempotencyService;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import com.kyongseo.banktransfer.transfer.dto.TransferResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 이체 진입점. 동시성 제어 방식(비관적/낙관적 락)을 골라 해당 구현에 위임하고, Idempotency-Key 재전송을 처리한다.
 * 트랜잭션 경계는 각 구현이 관리한다.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final PessimisticLockTransferService pessimisticLockTransferService;
    private final OptimisticLockTransferService optimisticLockTransferService;
    private final IdempotencyService idempotencyService;
    private final TransferProperties properties;

    /** 설정(bank.transfer.lock-strategy)의 기본 방식으로 이체한다. */
    public TransferResponse transfer(String fromAccountNumber, String toAccountNumber, long amount) {
        return transfer(fromAccountNumber, toAccountNumber, amount, properties.lockStrategy());
    }

    public TransferResponse transfer(String fromAccountNumber, String toAccountNumber, long amount,
                                     LockStrategy strategy) {
        return execute(fromAccountNumber, toAccountNumber, amount, strategy, null).response();
    }

    /**
     * Idempotency-Key를 지원하는 이체.
     *
     * @param strategy       null이면 설정의 기본 방식
     * @param idempotencyKey null이면 멱등성 처리 없이 이체
     */
    public TransferResult transfer(String fromAccountNumber, String toAccountNumber, long amount,
                                   LockStrategy strategy, String idempotencyKey) {
        LockStrategy lockStrategy = strategy != null ? strategy : properties.lockStrategy();
        if (idempotencyKey == null) {
            return execute(fromAccountNumber, toAccountNumber, amount, lockStrategy, null);
        }

        // 1) 이미 완료된 키면 락도 잡지 않고 저장된 응답 반환 (순차 재전송의 빠른 경로)
        Optional<TransferResponse> replay =
                idempotencyService.findReplay(idempotencyKey, fromAccountNumber, toAccountNumber, amount);
        if (replay.isPresent()) {
            return TransferResult.replayed(replay.get());
        }

        // 2) PENDING 키 행을 미리 만들어 커밋 (이미 있으면 그대로)
        idempotencyService.prepare(idempotencyKey, fromAccountNumber, toAccountNumber, amount);

        // 3) 이체 트랜잭션: 키 행 FOR UPDATE → (COMPLETED면 재전송) → 이체 → COMPLETED 기록 → 함께 커밋
        return execute(fromAccountNumber, toAccountNumber, amount, lockStrategy, idempotencyKey);
    }

    private TransferResult execute(String fromAccountNumber, String toAccountNumber, long amount,
                                   LockStrategy strategy, String idempotencyKey) {
        return switch (strategy) {
            case PESSIMISTIC ->
                    pessimisticLockTransferService.transfer(fromAccountNumber, toAccountNumber, amount, idempotencyKey);
            case OPTIMISTIC ->
                    optimisticLockTransferService.transfer(fromAccountNumber, toAccountNumber, amount, idempotencyKey);
        };
    }
}
