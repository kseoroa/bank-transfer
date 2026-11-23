package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String FROM = "111111111111";
    private static final String TO = "222222222222";

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransferService transferService;

    private Account from;
    private Account to;

    @BeforeEach
    void setUp() {
        from = new Account(FROM, "보내는사람", 10_000);
        to = new Account(TO, "받는사람", 5_000);
    }

    @Test
    @DisplayName("이체하면 보낸 계좌에서 빠지고 받는 계좌에 더해진다")
    void transfer_success() {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.of(from));
        given(accountRepository.findByAccountNumber(TO)).willReturn(Optional.of(to));

        TransferResponse response = transferService.transfer(FROM, TO, 3_000);

        assertThat(from.getBalance()).isEqualTo(7_000);
        assertThat(to.getBalance()).isEqualTo(8_000);
        assertThat(response.fromBalanceAfter()).isEqualTo(7_000);
        assertThat(response.amount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("잔액 전액을 이체할 수 있다")
    void transfer_entireBalance() {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.of(from));
        given(accountRepository.findByAccountNumber(TO)).willReturn(Optional.of(to));

        transferService.transfer(FROM, TO, 10_000);

        assertThat(from.getBalance()).isZero();
        assertThat(to.getBalance()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 예외가 발생하고 두 계좌 잔액은 변하지 않는다")
    void transfer_insufficientBalance() {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.of(from));
        given(accountRepository.findByAccountNumber(TO)).willReturn(Optional.of(to));

        assertThatThrownBy(() -> transferService.transfer(FROM, TO, 10_001))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(from.getBalance()).isEqualTo(10_000);
        assertThat(to.getBalance()).isEqualTo(5_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -1_000})
    @DisplayName("이체 금액이 0 이하면 예외가 발생한다")
    void transfer_nonPositiveAmount(long amount) {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.of(from));
        given(accountRepository.findByAccountNumber(TO)).willReturn(Optional.of(to));

        assertThatThrownBy(() -> transferService.transfer(FROM, TO, amount))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);

        assertThat(from.getBalance()).isEqualTo(10_000);
        assertThat(to.getBalance()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("보내는 계좌가 없으면 예외가 발생한다")
    void transfer_fromAccountNotFound() {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(FROM, TO, 1_000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("받는 계좌가 없으면 예외가 발생하고 보낸 계좌 잔액은 변하지 않는다")
    void transfer_toAccountNotFound() {
        given(accountRepository.findByAccountNumber(FROM)).willReturn(Optional.of(from));
        given(accountRepository.findByAccountNumber(TO)).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(FROM, TO, 1_000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);

        assertThat(from.getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("같은 계좌로 이체하면 계좌 조회 없이 예외가 발생한다")
    void transfer_sameAccount() {
        assertThatThrownBy(() -> transferService.transfer(FROM, FROM, 1_000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);

        verify(accountRepository, never()).findByAccountNumber(anyString());
    }
}
