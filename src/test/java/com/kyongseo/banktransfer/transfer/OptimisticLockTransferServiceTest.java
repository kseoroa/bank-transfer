package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.idempotency.IdempotencyService;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OptimisticLockTransferServiceTest {

    private static final String A = "111111111111";
    private static final String B = "222222222222";
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private IdempotencyService idempotencyService;

    private TransferMetrics metrics;
    private OptimisticLockTransferService transferService;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        metrics = new TransferMetrics();
        TransferProperties properties = new TransferProperties(
                LockStrategy.OPTIMISTIC, new TransferProperties.Optimistic(MAX_ATTEMPTS, 0));
        transferService = new OptimisticLockTransferService(
                accountRepository, idempotencyService, transactionTemplate, metrics, properties);

        accountA = new Account(A, "A", 10_000);
        accountB = new Account(B, "B", 5_000);
    }

    /** 실제 트랜잭션 없이 콜백만 실행한다. */
    private static Object runCallback(InvocationOnMock invocation) {
        TransactionCallback<?> callback = invocation.getArgument(0);
        return callback.doInTransaction(null);
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException(Account.class, 1L);
    }

    private void givenBothAccountsExist() {
        given(accountRepository.findByAccountNumber(A)).willReturn(Optional.of(accountA));
        given(accountRepository.findByAccountNumber(B)).willReturn(Optional.of(accountB));
    }

    @Test
    @DisplayName("충돌이 없으면 한 번에 성공하고 재시도하지 않는다")
    void success_withoutConflict() {
        givenBothAccountsExist();
        given(transactionTemplate.execute(any())).willAnswer(OptimisticLockTransferServiceTest::runCallback);

        TransferResponse response = transferService.transfer(A, B, 3_000);

        assertThat(response.fromBalanceAfter()).isEqualTo(7_000);
        assertThat(accountB.getBalance()).isEqualTo(8_000);
        assertThat(metrics.optimisticRetries()).isZero();
        verify(transactionTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("충돌이 2번 나면 재시도해서 3번째 시도에 성공한다")
    void success_afterRetries() {
        givenBothAccountsExist();
        given(transactionTemplate.execute(any()))
                .willThrow(conflict())
                .willThrow(conflict())
                .willAnswer(OptimisticLockTransferServiceTest::runCallback);

        transferService.transfer(A, B, 3_000);

        assertThat(accountA.getBalance()).isEqualTo(7_000);
        assertThat(accountB.getBalance()).isEqualTo(8_000);
        assertThat(metrics.optimisticRetries()).isEqualTo(2);
        assertThat(metrics.optimisticConflictFailures()).isZero();
        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    @DisplayName("최대 시도 횟수까지 모두 충돌하면 TRANSFER_CONFLICT로 실패한다")
    void fail_whenAllAttemptsConflict() {
        given(transactionTemplate.execute(any())).willThrow(conflict());

        assertThatThrownBy(() -> transferService.transfer(A, B, 3_000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TRANSFER_CONFLICT);

        verify(transactionTemplate, times(MAX_ATTEMPTS)).execute(any());
        assertThat(metrics.optimisticRetries()).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(metrics.optimisticConflictFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("잔액 부족은 재시도해도 결과가 같으므로 재시도하지 않는다")
    void noRetry_onInsufficientBalance() {
        givenBothAccountsExist();
        given(transactionTemplate.execute(any())).willAnswer(OptimisticLockTransferServiceTest::runCallback);

        assertThatThrownBy(() -> transferService.transfer(A, B, 10_001))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        verify(transactionTemplate, times(1)).execute(any());
        assertThat(metrics.optimisticRetries()).isZero();
    }

    @Test
    @DisplayName("같은 계좌로 이체하면 트랜잭션을 시작하지 않고 예외가 발생한다")
    void sameAccount() {
        assertThatThrownBy(() -> transferService.transfer(A, A, 1_000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);

        verify(transactionTemplate, never()).execute(any());
    }
}
