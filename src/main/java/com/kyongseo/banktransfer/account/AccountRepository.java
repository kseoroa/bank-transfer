package com.kyongseo.banktransfer.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * 락 순서를 정하기 위해 id만 조회한다.
     * 엔티티 전체를 읽으면 영속성 컨텍스트에 오래된 잔액이 남아, 이후 FOR UPDATE 결과가 무시될 수 있다.
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /** SELECT ... FOR UPDATE: 커밋/롤백 전까지 다른 트랜잭션의 같은 행 락 획득을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
