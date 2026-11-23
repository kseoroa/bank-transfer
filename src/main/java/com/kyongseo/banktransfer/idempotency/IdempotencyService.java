package com.kyongseo.banktransfer.idempotency;

import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key 처리 (MySQL 저장).
 *
 * <pre>
 * 1) findReplay : 이미 COMPLETED면 저장된 응답 반환 (읽기 전용 트랜잭션)
 * 2) prepare    : PENDING 행을 미리 만들고 바로 커밋 (짧은 트랜잭션)
 * 3) lock       : 이체 트랜잭션 안에서 키 행을 FOR UPDATE → 같은 키 요청은 여기서 한 줄로 대기
 * 4) complete   : 이체와 같은 트랜잭션에서 COMPLETED + 응답 저장 → 이체와 함께 커밋
 * </pre>
 *
 * <p>2)에서 행을 먼저 만들어 두는 이유: 존재하지 않는 키를 이체 트랜잭션에서 INSERT하면,
 * 첫 트랜잭션이 롤백될 때 대기하던 INSERT들끼리 데드락이 난다(공유 락 → 배타 락 경쟁).
 * 이미 있는 행에 대한 FOR UPDATE 대기는 단순한 줄서기라 데드락이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    /**
     * 커밋된 기록이 있으면: 내용이 다르면 IDEMPOTENCY_KEY_REUSED, COMPLETED면 저장된 응답, PENDING이면 empty.
     */
    @Transactional(readOnly = true)
    public Optional<TransferResponse> findReplay(String key, String fromAccountNumber, String toAccountNumber,
                                                 long amount) {
        return repository.findByIdempotencyKey(key)
                .map(record -> verifySameRequest(record, fromAccountNumber, toAccountNumber, amount))
                .filter(IdempotencyRecord::isCompleted)
                .map(IdempotencyRecord::toResponse);
    }

    /** 키 행이 없으면 PENDING으로 만들고 바로 커밋한다. 이미 있으면 그대로 둔다. */
    @Transactional
    public void prepare(String key, String fromAccountNumber, String toAccountNumber, long amount) {
        repository.insertIfAbsent(key, fromAccountNumber, toAccountNumber, amount);
    }

    /**
     * 이체 트랜잭션 안에서 키 행을 잠근다. key가 null이면 null.
     * 같은 키로 진행 중인 트랜잭션이 있으면 그쪽이 커밋/롤백할 때까지 대기한다.
     * FOR UPDATE는 최신 커밋 값을 읽으므로, 대기 후에는 앞선 요청의 COMPLETED 여부가 보인다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyRecord lock(String key, String fromAccountNumber, String toAccountNumber, long amount) {
        if (key == null) {
            return null;
        }
        IdempotencyRecord record = repository.findByIdempotencyKeyForUpdate(key)
                .orElseThrow(() -> new IllegalStateException("prepare()가 먼저 호출되어야 합니다: " + key));
        return verifySameRequest(record, fromAccountNumber, toAccountNumber, amount);
    }

    /** 이체와 같은 트랜잭션에서 결과를 기록한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotencyRecord record, TransferResponse response) {
        if (record != null) {
            record.complete(response);
        }
    }

    private IdempotencyRecord verifySameRequest(IdempotencyRecord record, String fromAccountNumber,
                                                String toAccountNumber, long amount) {
        if (!record.matches(fromAccountNumber, toAccountNumber, amount)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return record;
    }
}
