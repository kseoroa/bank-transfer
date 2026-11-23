package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kyongseo.banktransfer.idempotency.IdempotencyService;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import com.kyongseo.banktransfer.transfer.dto.TransferResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 방식 선택 + Idempotency-Key 흐름의 호출 순서 검증 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String A = "111111111111";
    private static final String B = "222222222222";
    private static final String KEY = "key-1";
    private static final TransferResponse RESPONSE = new TransferResponse(A, B, 1_000, 9_000);

    @Mock
    private PessimisticLockTransferService pessimistic;

    @Mock
    private OptimisticLockTransferService optimistic;

    @Mock
    private IdempotencyService idempotencyService;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        TransferProperties properties = new TransferProperties(
                LockStrategy.PESSIMISTIC, new TransferProperties.Optimistic(5, 0));
        transferService = new TransferService(pessimistic, optimistic, idempotencyService, properties);
    }

    @Test
    @DisplayName("키가 없으면 멱등성 처리 없이 바로 이체한다")
    void noKey() {
        given(pessimistic.transfer(A, B, 1_000, null)).willReturn(TransferResult.executed(RESPONSE));

        TransferResult result = transferService.transfer(A, B, 1_000, null, null);

        assertThat(result.replayed()).isFalse();
        verifyNoInteractions(idempotencyService);
    }

    @Test
    @DisplayName("이미 완료된 키면 키 행 준비도, 이체도 하지 않고 저장된 응답을 돌려준다")
    void completedKey_replaysWithoutTransfer() {
        given(idempotencyService.findReplay(KEY, A, B, 1_000)).willReturn(Optional.of(RESPONSE));

        TransferResult result = transferService.transfer(A, B, 1_000, null, KEY);

        assertThat(result).isEqualTo(TransferResult.replayed(RESPONSE));
        verify(idempotencyService, never()).prepare(anyString(), anyString(), anyString(), anyLong());
        verify(pessimistic, never()).transfer(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("완료되지 않은 키면 prepare(키 행 생성) 후에 이체를 실행한다")
    void newKey_preparesThenTransfers() {
        given(idempotencyService.findReplay(KEY, A, B, 1_000)).willReturn(Optional.empty());
        given(pessimistic.transfer(A, B, 1_000, KEY)).willReturn(TransferResult.executed(RESPONSE));

        TransferResult result = transferService.transfer(A, B, 1_000, null, KEY);

        assertThat(result.replayed()).isFalse();
        InOrder order = inOrder(idempotencyService, pessimistic);
        order.verify(idempotencyService).prepare(KEY, A, B, 1_000);
        order.verify(pessimistic).transfer(A, B, 1_000, KEY);
    }

    @Test
    @DisplayName("lockStrategy를 지정하면 해당 구현으로 위임한다")
    void explicitStrategy() {
        given(optimistic.transfer(A, B, 1_000, null)).willReturn(TransferResult.executed(RESPONSE));

        transferService.transfer(A, B, 1_000, LockStrategy.OPTIMISTIC, null);

        verify(optimistic).transfer(A, B, 1_000, null);
        verifyNoInteractions(pessimistic);
    }
}
