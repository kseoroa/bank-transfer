package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PessimisticLockTransferServiceTest {

    private static final String A = "111111111111";
    private static final String B = "222222222222";
    private static final Long A_ID = 1L;
    private static final Long B_ID = 2L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private PessimisticLockTransferService transferService;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        accountA = new Account(A, "A", 10_000);
        accountB = new Account(B, "B", 5_000);
        ReflectionTestUtils.setField(accountA, "id", A_ID);
        ReflectionTestUtils.setField(accountB, "id", B_ID);
    }

    private void givenBothAccountsExist() {
        given(accountRepository.findIdByAccountNumber(A)).willReturn(Optional.of(A_ID));
        given(accountRepository.findIdByAccountNumber(B)).willReturn(Optional.of(B_ID));
        given(accountRepository.findByIdForUpdate(A_ID)).willReturn(Optional.of(accountA));
        given(accountRepository.findByIdForUpdate(B_ID)).willReturn(Optional.of(accountB));
    }

    private static void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("이체하면 보낸 계좌에서 빠지고 받는 계좌에 더해진다")
    void transfer_success() {
        givenBothAccountsExist();

        TransferResponse response = transferService.transfer(A, B, 3_000);

        assertThat(accountA.getBalance()).isEqualTo(7_000);
        assertThat(accountB.getBalance()).isEqualTo(8_000);
        assertThat(response.fromBalanceAfter()).isEqualTo(7_000);
        assertThat(response.amount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("잔액 전액을 이체할 수 있다")
    void transfer_entireBalance() {
        givenBothAccountsExist();

        transferService.transfer(A, B, 10_000);

        assertThat(accountA.getBalance()).isZero();
        assertThat(accountB.getBalance()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 예외가 발생하고 두 계좌 잔액은 변하지 않는다")
    void transfer_insufficientBalance() {
        givenBothAccountsExist();

        assertErrorCode(() -> transferService.transfer(A, B, 10_001), ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(accountA.getBalance()).isEqualTo(10_000);
        assertThat(accountB.getBalance()).isEqualTo(5_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -1_000})
    @DisplayName("이체 금액이 0 이하면 예외가 발생한다")
    void transfer_nonPositiveAmount(long amount) {
        givenBothAccountsExist();

        assertErrorCode(() -> transferService.transfer(A, B, amount), ErrorCode.INVALID_AMOUNT);

        assertThat(accountA.getBalance()).isEqualTo(10_000);
        assertThat(accountB.getBalance()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("보내는 계좌가 없으면 락을 걸지 않고 예외가 발생한다")
    void transfer_fromAccountNotFound() {
        given(accountRepository.findIdByAccountNumber(A)).willReturn(Optional.empty());

        assertErrorCode(() -> transferService.transfer(A, B, 1_000), ErrorCode.ACCOUNT_NOT_FOUND);

        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("받는 계좌가 없으면 락을 걸지 않고 예외가 발생한다")
    void transfer_toAccountNotFound() {
        given(accountRepository.findIdByAccountNumber(A)).willReturn(Optional.of(A_ID));
        given(accountRepository.findIdByAccountNumber(B)).willReturn(Optional.empty());

        assertErrorCode(() -> transferService.transfer(A, B, 1_000), ErrorCode.ACCOUNT_NOT_FOUND);

        verify(accountRepository, never()).findByIdForUpdate(any());
        assertThat(accountA.getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("같은 계좌로 이체하면 계좌 조회 없이 예외가 발생한다")
    void transfer_sameAccount() {
        assertErrorCode(() -> transferService.transfer(A, A, 1_000), ErrorCode.SAME_ACCOUNT_TRANSFER);

        verify(accountRepository, never()).findIdByAccountNumber(anyString());
    }

    @Test
    @DisplayName("[데드락 방지] A(id 1)→B(id 2) 이체는 id 1, 2 순서로 락을 건다")
    void lockOrder_smallIdIsFrom() {
        givenBothAccountsExist();

        transferService.transfer(A, B, 1_000);

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(A_ID);
        order.verify(accountRepository).findByIdForUpdate(B_ID);
    }

    @Test
    @DisplayName("[데드락 방지] B(id 2)→A(id 1) 이체도 id 1, 2 순서로 락을 건다")
    void lockOrder_smallIdIsTo() {
        givenBothAccountsExist();

        transferService.transfer(B, A, 1_000);

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(A_ID);
        order.verify(accountRepository).findByIdForUpdate(B_ID);
        assertThat(accountB.getBalance()).isEqualTo(4_000);
        assertThat(accountA.getBalance()).isEqualTo(11_000);
    }
}
