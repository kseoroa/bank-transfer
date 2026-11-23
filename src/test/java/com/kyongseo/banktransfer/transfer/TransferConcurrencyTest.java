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
 * 같은 계좌(A)에 이체가 몰리는 상황. 2단계에서 lost update를 재현한 조건 그대로
 * 비관적 락과 낙관적 락을 각각 실행해 비교한다.
 */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class TransferConcurrencyTest {

    private static final long INITIAL_BALANCE_A = 100_000;
    private static final long TRANSFER_AMOUNT = 1_000;
    private static final int REQUEST_COUNT = 100;
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
        accountA = accountRepository.save(new Account("CA" + suffix, "A", INITIAL_BALANCE_A));
        accountB = accountRepository.save(new Account("CB" + suffix, "B", 0));
        metrics.reset();
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAllById(List.of(accountA.getId(), accountB.getId()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("A(100,000원)에서 B로 1,000원씩 100건 동시 이체: 성공한 이체는 하나도 유실되지 않아야 한다")
    void concurrentTransfers(LockStrategy strategy) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await();
                    transferService.transfer(
                            accountA.getAccountNumber(), accountB.getAccountNumber(), TRANSFER_AMOUNT, strategy);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(failureKey(e), k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        long startedAt = System.nanoTime();
        startSignal.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        executor.shutdown();

        long actualA = accountRepository.findById(accountA.getId()).orElseThrow().getBalance();
        long actualB = accountRepository.findById(accountB.getId()).orElseThrow().getBalance();
        int success = successCount.get();
        int failureCount = failures.values().stream().mapToInt(AtomicInteger::get).sum();
        // 성공한 이체만큼 정확히 이동했어야 한다 (lost update가 있으면 깨진다)
        long expectedA = INITIAL_BALANCE_A - TRANSFER_AMOUNT * success;
        long expectedB = TRANSFER_AMOUNT * success;

        System.out.println();
        System.out.printf("========== 동시 이체 결과 [%s] ==========%n", strategy);
        System.out.printf("요청 %d건 / 성공 %d건 / 실패 %d건 %s / 60초 내 완료: %s%n",
                REQUEST_COUNT, success, failureCount, failures.isEmpty() ? "" : failures, finished);
        System.out.printf("재시도 %d회 (낙관적 락 충돌 후 재시도) / 걸린 시간: %,d ms%n",
                metrics.optimisticRetries(), elapsedMillis);
        System.out.printf("%-8s %12s %12s%n", "", "기대값", "실제값");
        System.out.printf("%-8s %,12d %,12d%n", "A 잔액", expectedA, actualA);
        System.out.printf("%-8s %,12d %,12d%n", "B 잔액", expectedB, actualB);
        System.out.println("(기대값 = 성공 건수만큼 이동한 잔액)");
        System.out.println("==========================================");
        System.out.println();

        assertAll(
                () -> assertThat(finished).as("시간 내 완료").isTrue(),
                () -> assertThat(success + failureCount).as("응답 건수").isEqualTo(REQUEST_COUNT),
                () -> assertThat(actualA).as("A 잔액 (성공 건수 기준)").isEqualTo(expectedA),
                () -> assertThat(actualB).as("B 잔액 (성공 건수 기준)").isEqualTo(expectedB),
                () -> {
                    if (strategy == LockStrategy.PESSIMISTIC) {
                        assertThat(success).as("비관적 락은 전부 성공").isEqualTo(REQUEST_COUNT);
                    } else {
                        assertThat(failures.keySet()).as("낙관적 락 실패 사유는 재시도 소진뿐")
                                .isSubsetOf(ErrorCode.TRANSFER_CONFLICT.name());
                    }
                }
        );
    }

    private static String failureKey(Exception e) {
        return e instanceof BusinessException be ? be.getErrorCode().name() : e.getClass().getSimpleName();
    }
}
