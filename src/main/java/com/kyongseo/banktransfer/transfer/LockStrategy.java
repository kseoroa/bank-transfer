package com.kyongseo.banktransfer.transfer;

public enum LockStrategy {
    /** SELECT ... FOR UPDATE로 먼저 잠그고 처리한다. 충돌이 나면 대기한다. */
    PESSIMISTIC,
    /** 락 없이 처리하고 커밋 시 @Version으로 충돌을 감지한다. 충돌이 나면 재시도한다. */
    OPTIMISTIC
}
