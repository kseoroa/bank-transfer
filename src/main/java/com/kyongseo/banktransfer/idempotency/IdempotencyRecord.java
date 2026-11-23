package com.kyongseo.banktransfer.idempotency;

import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 멱등성 키 기록.
 *
 * <p>PENDING 행은 IdempotencyService.prepare()가 짧은 트랜잭션으로 미리 만들어 둔다.
 * 같은 키 요청들은 이체 트랜잭션에서 이 행을 SELECT ... FOR UPDATE로 잠가 한 줄로 처리되고,
 * COMPLETED 전환과 응답 저장은 이체와 같은 트랜잭션에서 커밋된다.
 */
@Entity
@Table(
        name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    public enum Status { PENDING, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String fromAccountNumber;

    @Column(nullable = false, length = 20)
    private String toAccountNumber;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** 첫 처리 결과. COMPLETED일 때만 값이 있다. */
    private Long fromBalanceAfter;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public void complete(TransferResponse response) {
        this.status = Status.COMPLETED;
        this.fromBalanceAfter = response.fromBalanceAfter();
    }

    /** 같은 키로 온 요청이 처음 요청과 내용이 같은지 */
    public boolean matches(String fromAccountNumber, String toAccountNumber, long amount) {
        return this.fromAccountNumber.equals(fromAccountNumber)
                && this.toAccountNumber.equals(toAccountNumber)
                && this.amount == amount;
    }

    public TransferResponse toResponse() {
        return new TransferResponse(fromAccountNumber, toAccountNumber, amount, fromBalanceAfter);
    }
}
