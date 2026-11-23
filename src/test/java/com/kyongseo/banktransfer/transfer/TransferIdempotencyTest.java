package com.kyongseo.banktransfer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.kyongseo.banktransfer.account.Account;
import com.kyongseo.banktransfer.account.AccountRepository;
import com.kyongseo.banktransfer.global.exception.BusinessException;
import com.kyongseo.banktransfer.global.exception.ErrorCode;
import com.kyongseo.banktransfer.idempotency.IdempotencyRecordRepository;
import com.kyongseo.banktransfer.transfer.dto.TransferResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 6단계: 같은 Idempotency-Key로 온 요청은 동시에 들어와도 이체가 한 번만 일어나야 한다. (실제 MySQL)
 */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class TransferIdempotencyTest {

    private static final long INITIAL_BALANCE = 100_000;
    private static final long AMOUNT = 1_000;
    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private Account accountA;
    private Account accountB;
    private final List<String> usedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        accountA = accountRepository.save(new Account("IA" + suffix, "A", INITIAL_BALANCE));
        accountB = accountRepository.save(new Account("IB" + suffix, "B", 0));
    }

    @AfterEach
    void tearDown() {
        usedKeys.forEach(key -> idempotencyRecordRepository.findByIdempotencyKey(key)
                .ifPresent(idempotencyRecordRepository::delete));
        accountRepository.deleteAllById(List.of(accountA.getId(), accountB.getId()));
    }

    private String newKey() {
        String key = UUID.randomUUID().toString();
        usedKeys.add(key);
        return key;
    }

    private long balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("같은 키로 10건을 동시에 보내도 이체는 1번만 되고, 10건 모두 같은 응답을 받는다")
    void sameKeyConcurrentRequests(LockStrategy strategy) throws InterruptedException {
        String key = newKey();
        List<TransferResult> results = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        runConcurrently(() -> results.add(transferService.transfer(
                accountA.getAccountNumber(), accountB.getAccountNumber(), AMOUNT, strategy, key)), failures);

        long executed = results.stream().filter(r -> !r.replayed()).count();
        long replayed = results.stream().filter(TransferResult::replayed).count();
        long actualA = balanceOf(accountA);
        long actualB = balanceOf(accountB);

        System.out.println();
        System.out.printf("========== 같은 키 %d건 동시 요청 [%s] ==========%n", CONCURRENT_REQUESTS, strategy);
        System.out.printf("성공 응답 %d건 (실제 이체 %d, 재전송 %d) / 실패 %s%n",
                results.size(), executed, replayed, failures.isEmpty() ? "0건" : failures);
        System.out.printf("응답의 이체 후 잔액: %s%n",
                results.stream().map(r -> r.response().fromBalanceAfter()).distinct().toList());
        System.out.printf("%-6s %10s %10s%n", "", "기대값", "실제값");
        System.out.printf("%-6s %,10d %,10d%n", "A 잔액", INITIAL_BALANCE - AMOUNT, actualA);
        System.out.printf("%-6s %,10d %,10d%n", "B 잔액", AMOUNT, actualB);
        System.out.println("==============================================");

        assertAll(
                () -> assertThat(failures).as("실패 응답 없음").isEmpty(),
                () -> assertThat(results).as("응답 수").hasSize(CONCURRENT_REQUESTS),
                () -> assertThat(executed).as("실제 이체 횟수").isEqualTo(1),
                () -> assertThat(replayed).as("재전송 응답 수").isEqualTo(CONCURRENT_REQUESTS - 1),
                () -> assertThat(results).as("모든 응답이 첫 결과와 동일")
                        .extracting(TransferResult::response).containsOnly(results.getFirst().response()),
                () -> assertThat(actualA).as("A 잔액").isEqualTo(INITIAL_BALANCE - AMOUNT),
                () -> assertThat(actualB).as("B 잔액").isEqualTo(AMOUNT),
                () -> assertThat(idempotencyRecordRepository.findByIdempotencyKey(key)).as("키 기록").isPresent()
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("같은 키로 10건 동시 요청했는데 잔액 부족이면 데드락 없이 10건 모두 잔액 부족으로 실패하고 키는 완료되지 않는다")
    void sameKeyConcurrentRequests_whenTransferFails(LockStrategy strategy) throws InterruptedException {
        String key = newKey();
        long tooMuch = INITIAL_BALANCE + 1;
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        runConcurrently(() -> transferService.transfer(
                accountA.getAccountNumber(), accountB.getAccountNumber(), tooMuch, strategy, key), failures);

        System.out.printf("[%s] 같은 키 %d건 동시 요청, 잔액 부족: 실패 %s%n", strategy, CONCURRENT_REQUESTS, failures);

        assertAll(
                () -> assertThat(failures).as("모두 잔액 부족으로 실패")
                        .containsOnlyKeys(ErrorCode.INSUFFICIENT_BALANCE.name()),
                () -> assertThat(failures.get(ErrorCode.INSUFFICIENT_BALANCE.name())).hasValue(CONCURRENT_REQUESTS),
                () -> assertThat(balanceOf(accountA)).isEqualTo(INITIAL_BALANCE),
                () -> assertThat(idempotencyRecordRepository.findByIdempotencyKey(key)).as("키는 PENDING으로 남음")
                        .hasValueSatisfying(record -> assertThat(record.isCompleted()).isFalse())
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("완료된 키로 다시 보내면 이체 없이 같은 응답을 돌려준다")
    void sameKeySequentialRetry(LockStrategy strategy) {
        String key = newKey();

        TransferResult first = transferService.transfer(
                accountA.getAccountNumber(), accountB.getAccountNumber(), AMOUNT, strategy, key);
        TransferResult second = transferService.transfer(
                accountA.getAccountNumber(), accountB.getAccountNumber(), AMOUNT, strategy, key);

        assertAll(
                () -> assertThat(first.replayed()).isFalse(),
                () -> assertThat(second.replayed()).isTrue(),
                () -> assertThat(second.response()).isEqualTo(first.response()),
                () -> assertThat(balanceOf(accountA)).isEqualTo(INITIAL_BALANCE - AMOUNT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LockStrategy.class)
    @DisplayName("같은 키로 다른 내용을 보내면 IDEMPOTENCY_KEY_REUSED로 거절한다")
    void sameKeyDifferentRequest(LockStrategy strategy) {
        String key = newKey();
        transferService.transfer(accountA.getAccountNumber(), accountB.getAccountNumber(), AMOUNT, strategy, key);

        assertThatThrownBy(() -> transferService.transfer(
                accountA.getAccountNumber(), accountB.getAccountNumber(), AMOUNT * 2, strategy, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThat(balanceOf(accountA)).isEqualTo(INITIAL_BALANCE - AMOUNT);
    }

    private void runConcurrently(Runnable task, Map<String, AtomicInteger> failures) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await();
                    task.run();
                } catch (Exception e) {
                    String key = e instanceof BusinessException be
                            ? be.getErrorCode().name() : e.getClass().getSimpleName();
                    failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        startSignal.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("60초 내 완료").isTrue();
        executor.shutdown();
    }
}
