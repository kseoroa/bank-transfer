package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 3단계: 비관적 락 환경에서 A→B, B→A 이체가 동시에 일어날 때 데드락이 생기지 않아야 한다.
 * 락을 "보내는 계좌 → 받는 계좌" 순서로 잡으면 두 방향이 서로의 락을 기다리며 데드락이 난다.
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

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        accountA = accountRepository.save(new Account("DA" + suffix, "A", INITIAL_BALANCE));
        accountB = accountRepository.save(new Account("DB" + suffix, "B", INITIAL_BALANCE));
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAllById(List.of(accountA.getId(), accountB.getId()));
    }

    @Test
    @DisplayName("A→B 50건과 B→A 50건을 동시에 이체해도 데드락 없이 모두 성공하고 잔액이 보존된다")
    void bidirectionalTransfers() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            boolean aToB = i % 2 == 0;
            String from = aToB ? accountA.getAccountNumber() : accountB.getAccountNumber();
            String to = aToB ? accountB.getAccountNumber() : accountA.getAccountNumber();
            executor.submit(() -> {
                try {
                    startSignal.await();
                    transferService.transfer(from, to, TRANSFER_AMOUNT);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
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
        int failureCount = failures.values().stream().mapToInt(AtomicInteger::get).sum();

        System.out.println();
        System.out.println("========== 양방향 동시 이체 결과 ==========");
        System.out.printf("요청 %d건 (A→B %d, B→A %d) / 성공 %d건 / 실패 %s / 120초 내 완료: %s%n",
                REQUEST_COUNT, (REQUEST_COUNT + 1) / 2, REQUEST_COUNT / 2,
                successCount.get(), failures.isEmpty() ? "0건" : failures, finished);
        System.out.printf("걸린 시간: %,d ms%n", elapsedMillis);
        System.out.printf("%-8s %12s %12s%n", "", "기대값", "실제값");
        System.out.printf("%-8s %,12d %,12d%n", "A 잔액", INITIAL_BALANCE, actualA);
        System.out.printf("%-8s %,12d %,12d%n", "B 잔액", INITIAL_BALANCE, actualB);
        System.out.println("==========================================");
        System.out.println();

        assertAll(
                () -> assertThat(finished).as("시간 내 완료").isTrue(),
                () -> assertThat(failureCount).as("실패 건수 (데드락 포함)").isZero(),
                () -> assertThat(actualA).as("A 잔액").isEqualTo(INITIAL_BALANCE),
                () -> assertThat(actualB).as("B 잔액").isEqualTo(INITIAL_BALANCE)
        );
    }
}
