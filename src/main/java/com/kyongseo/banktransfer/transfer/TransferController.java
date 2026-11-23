package com.kyongseo.banktransfer.transfer;

import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.transfer.dto.TransferRequest;
import com.kyongseo.banktransfer.transfer.dto.TransferResponse;
import com.kyongseo.banktransfer.transfer.dto.TransferResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final TransferService transferService;

    /**
     * @param lockStrategy   비교 실험용. 생략하면 bank.transfer.lock-strategy 설정값을 쓴다.
     * @param idempotencyKey 같은 키로 다시 요청하면 이체하지 않고 첫 결과를 돌려준다 (응답 헤더 Idempotent-Replayed: true)
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestParam(required = false) LockStrategy lockStrategy,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        TransferResult result = transferService.transfer(request.fromAccountNumber(), request.toAccountNumber(),
                request.amount(), lockStrategy, idempotencyKey);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(result.response());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
