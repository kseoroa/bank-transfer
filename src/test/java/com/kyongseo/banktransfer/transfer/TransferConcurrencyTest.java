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
 * 2단계: 락 없는 이체에서 동시 요청 시 lost update가 발생함을 실제 MySQL로 재현한다.
 * 현재는 실패하는 것이 정상(재현 성공)이며, 3단계에서 동시성 제어를 넣으면 통과해야 한다.
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

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        accountA = accountRepository.save(new Account("CA" + suffix, "A", INITIAL_BALANCE_A));
        accountB = accountRepository.save(new Account("CB" + suffix, "B", 0));
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAllById(List.of(accountA.getId(), accountB.getId()));
    }

    @Test
    @DisplayName("A(100,000원)에서 B로 1,000원씩 100건 동시 이체하면 A=0, B=100,000이어야 한다")
    void concurrentTransfers() throws InterruptedException {
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
                            accountA.getAccountNumber(), accountB.getAccountNumber(), TRANSFER_AMOUNT);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startSignal.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long actualA = accountRepository.findById(accountA.getId()).orElseThrow().getBalance();
        long actualB = accountRepository.findById(accountB.getId()).orElseThrow().getBalance();
        long expectedA = INITIAL_BALANCE_A - TRANSFER_AMOUNT * REQUEST_COUNT;
        long expectedB = TRANSFER_AMOUNT * REQUEST_COUNT;

        printReport(finished, successCount.get(), failures, expectedA, actualA, expectedB, actualB);

        assertAll(
                () -> assertThat(actualA).as("A 잔액").isEqualTo(expectedA),
                () -> assertThat(actualB).as("B 잔액").isEqualTo(expectedB)
        );
    }

    private void printReport(boolean finished, int success, Map<String, AtomicInteger> failures,
                             long expectedA, long actualA, long expectedB, long actualB) {
        System.out.println();
        System.out.println("========== 동시 이체 결과 ==========");
        System.out.printf("요청 %d건 / 성공 %d건 / 실패 %s / 60초 내 완료: %s%n",
                REQUEST_COUNT, success, failures.isEmpty() ? "0건" : failures, finished);
        System.out.printf("%-8s %12s %12s %12s%n", "", "기대값", "실제값", "차이");
        System.out.printf("%-8s %,12d %,12d %,12d%n", "A 잔액", expectedA, actualA, actualA - expectedA);
        System.out.printf("%-8s %,12d %,12d %,12d%n", "B 잔액", expectedB, actualB, actualB - expectedB);
        System.out.printf("%-8s %,12d %,12d %,12d%n", "A+B 합계",
                expectedA + expectedB, actualA + actualB, (actualA + actualB) - (expectedA + expectedB));
        System.out.println("====================================");
        System.out.println();
    }
}
