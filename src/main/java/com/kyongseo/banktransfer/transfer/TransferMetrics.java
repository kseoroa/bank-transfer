package com.kyongseo.banktransfer.transfer;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 낙관적 락 재시도 통계. 비관적/낙관적 방식 비교 실험용 카운터.
 */
@Component
public class TransferMetrics {

    private final AtomicLong optimisticRetries = new AtomicLong();
    private final AtomicLong optimisticConflictFailures = new AtomicLong();

    public void recordOptimisticRetry() {
        optimisticRetries.incrementAndGet();
    }

    public void recordOptimisticConflictFailure() {
        optimisticConflictFailures.incrementAndGet();
    }

    public long optimisticRetries() {
        return optimisticRetries.get();
    }

    public long optimisticConflictFailures() {
        return optimisticConflictFailures.get();
    }

    public void reset() {
        optimisticRetries.set(0);
        optimisticConflictFailures.set(0);
    }
}
