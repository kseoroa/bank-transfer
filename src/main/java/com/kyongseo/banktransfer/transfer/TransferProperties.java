package com.kyongseo.banktransfer.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * bank.transfer.* 설정.
 *
 * @param lockStrategy 요청에 lockStrategy 파라미터가 없을 때 쓰는 기본 방식
 * @param optimistic   낙관적 락 재시도 정책
 */
@ConfigurationProperties("bank.transfer")
public record TransferProperties(
        @DefaultValue("PESSIMISTIC") LockStrategy lockStrategy,
        @DefaultValue Optimistic optimistic
) {

    /**
     * @param maxAttempts   최초 시도를 포함한 최대 시도 횟수
     * @param backoffMillis 재시도 전 기본 대기 시간. 실제로는 [backoff, 2*backoff] 사이 랜덤(jitter)
     */
    public record Optimistic(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("20") long backoffMillis
    ) {
    }
}
