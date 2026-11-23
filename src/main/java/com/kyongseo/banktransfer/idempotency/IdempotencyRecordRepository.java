package com.kyongseo.banktransfer.idempotency;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * 키가 없을 때만 PENDING 행을 만든다. 이미 있으면 아무것도 바꾸지 않는다 (id = id).
     * INSERT IGNORE와 달리 중복 키 외의 에러(길이 초과 등)는 그대로 에러로 남는다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_record
                (idempotency_key, from_account_number, to_account_number, amount, status, created_at)
            VALUES (:key, :from, :to, :amount, 'PENDING', NOW(6))
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("from") String fromAccountNumber,
                       @Param("to") String toAccountNumber, @Param("amount") long amount);

    /** 같은 키 요청을 한 줄로 세우기 위한 행 락 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecord r where r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByIdempotencyKeyForUpdate(@Param("key") String key);
}
