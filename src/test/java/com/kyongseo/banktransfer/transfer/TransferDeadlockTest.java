package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A→B, B→A 이체가 동시에 일어날 때 데드락이 없어야 한다.
 * 비관적 락: 계좌 id 순서로 FOR UPDATE / 낙관적 락: hibernate.order_updates로 UPDATE를 PK 순 정렬.
 */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class TransferDeadlockTest {

    private static final long INITIAL_BALANCE = 100_000;
    private static final long TRANSFER_AMOUNT = 1_000;
    private static final int REQUEST_COUNT = 100; // 짝수: A→B, 홀수: B→A
    private static final int THREAD_POOL_SIZE = 32;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferMetrics metrics;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        accountA = accountRepository.save(new Account("DA" + suffix, "A", INITIAL_BALANCE));
        accountB = accountRepository.save(new Account("DB" + suffix, "B", INITIAL_BALANCE));
        metrics.reset();
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAllById(List.of(accountA.getId(), accountB.getId()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("A→B 50건과 B→A 50건을 동시에 이체해도 데드락이 없고 잔액이 성공 건수와 일치한다")
    void bidirectionalTransfers(LockStrategy strategy) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successAtoB = new AtomicInteger();
        AtomicInteger successBtoA = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            boolean aToB = i % 2 == 0;
            String from = aToB ? accountA.getAccountNumber() : accountB.getAccountNumber();
            String to = aToB ? accountB.getAccountNumber() : accountA.getAccountNumber();
            executor.submit(() -> {
                try {
                    startSignal.await();
                    transferService.transfer(from, to, TRANSFER_AMOUNT, strategy);
                    (aToB ? successAtoB : successBtoA).incrementAndGet();
                } catch (Exception e) {
                    String key = e instanceof BusinessException be
                            ? be.getErrorCode().name() : e.getClass().getSimpleName();
                    failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        long startedAt = System.nanoTime();
        startSignal.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        executor.shutdown();

        long actualA = accountRepository.findById(accountA.getId()).orElseThrow().getBalance();
        long actualB = accountRepository.findById(accountB.getId()).orElseThrow().getBalance();
        int success = successAtoB.get() + successBtoA.get();
        int failureCount = failures.values().stream().mapToInt(AtomicInteger::get).sum();
        long net = TRANSFER_AMOUNT * (successBtoA.get() - successAtoB.get()); // A 입장 순증감
        long expectedA = INITIAL_BALANCE + net;
        long expectedB = INITIAL_BALANCE - net;

        System.out.println();
        System.out.printf("========== 양방향 동시 이체 결과 [%s] ==========%n", strategy);
        System.out.printf("요청 %d건 / 성공 %d건 (A→B %d, B→A %d) / 실패 %d건 %s / 120초 내 완료: %s%n",
                REQUEST_COUNT, success, successAtoB.get(), successBtoA.get(), failureCount,
                failures.isEmpty() ? "" : failures, finished);
        System.out.printf("재시도 %d회 / 걸린 시간: %,d ms%n", metrics.optimisticRetries(), elapsedMillis);
        System.out.printf("%-8s %12s %12s%n", "", "기대값", "실제값");
        System.out.printf("%-8s %,12d %,12d%n", "A 잔액", expectedA, actualA);
        System.out.printf("%-8s %,12d %,12d%n", "B 잔액", expectedB, actualB);
        System.out.println("(기대값 = 방향별 성공 건수만큼 이동한 잔액)");
        System.out.println("================================================");
        System.out.println();

        assertAll(
                () -> assertThat(finished).as("시간 내 완료").isTrue(),
                () -> assertThat(failures).as("데드락 실패 없음").doesNotContainKey("CannotAcquireLockException"),
                () -> assertThat(actualA).as("A 잔액").isEqualTo(expectedA),
                () -> assertThat(actualB).as("B 잔액").isEqualTo(expectedB),
                () -> {
                    if (strategy == LockStrategy.PESSIMISTIC) {
                        assertThat(failureCount).as("비관적 락은 전부 성공").isZero();
                    } else {
                        assertThat(failures.keySet()).as("낙관적 락 실패 사유는 재시도 소진뿐")
                                .isSubsetOf(ErrorCode.TRANSFER_CONFLICT.name());
                    }
                }
        );
    }
}
