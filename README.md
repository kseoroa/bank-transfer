# bank-transfer

계좌 이체 API. **동시에 이체 요청이 몰려도 돈이 틀어지지 않게** 만드는 과정을 단계별로 재현하고 해결한 개인 프로젝트입니다.

- 락 없이 100건을 동시에 이체하면 **100건 모두 성공 응답을 받지만 10건만 반영되는 문제**를 재현했습니다.
- 비관적 락과 낙관적 락으로 각각 해결하고, 같은 조건에서 성공·실패·재시도·잔액·시간을 비교했습니다.
- 같은 요청이 두 번 와도 한 번만 이체되도록 `Idempotency-Key`를 지원합니다.

## 기술 스택

Java 21 · Spring Boot 4.2 · Spring Data JPA (Hibernate) · MySQL 8.0 · Gradle · JUnit 5 / Mockito · k6

## 문서

| 문서 | 내용 |
|---|---|
| [docs/stages.md](docs/stages.md) | **1~6단계 작업 기록**: 단계마다 무엇을, 왜 했는지 쉽게 정리 |
| [docs/concurrency.md](docs/concurrency.md) | 동시 이체 문제와 원인, 비관적/낙관적 락 비교, 면접 예상 질문 |
| [k6/README.md](k6/README.md) | k6 부하 테스트 실행 방법과 측정 결과 |

## 실행

```bash
docker compose up -d     # MySQL 8 (:3306), Redis 7 (:6379)
./gradlew bootRun        # http://localhost:8080
```

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/accounts` | 계좌 생성 `{"ownerName": "홍길동", "initialBalance": 10000}` (계좌번호는 서버가 생성) |
| GET | `/api/accounts/{accountNumber}` | 잔액 조회 |
| POST | `/api/transfers` | 이체 `{"fromAccountNumber": "...", "toAccountNumber": "...", "amount": 1000}` |

이체 API 옵션:

| 옵션 | 위치 | 설명 |
|---|---|---|
| `lockStrategy` | 쿼리 파라미터 | `PESSIMISTIC` 또는 `OPTIMISTIC`. 생략하면 설정값 `bank.transfer.lock-strategy` (기본 `PESSIMISTIC`) |
| `Idempotency-Key` | 요청 헤더 | 같은 키로 다시 보내면 이체하지 않고 첫 결과를 반환. 응답 헤더 `Idempotent-Replayed: true` |

에러는 `{"code": "...", "message": "..."}` 형식입니다.

| 코드 | HTTP | 상황 |
|---|---|---|
| `INSUFFICIENT_BALANCE` | 409 | 잔액 부족 |
| `TRANSFER_CONFLICT` | 409 | 낙관적 락 재시도를 다 써도 충돌 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 같은 키에 다른 내용 |
| `ACCOUNT_NOT_FOUND` | 404 | 계좌 없음 |
| `INVALID_INPUT` 등 | 400 | 입력 오류 |

## 테스트

```bash
./gradlew test                                               # 전체 (MySQL 필요: docker compose up -d)
./gradlew test --tests '*TransferConcurrencyTest'            # 100건 동시 이체 (두 락 방식)
./gradlew test --tests '*TransferIdempotencyTest'            # 같은 키 10건 동시 요청
```

| 테스트 | 확인하는 것 |
|---|---|
| `PessimisticLockTransferServiceTest`, `OptimisticLockTransferServiceTest`, `TransferServiceTest` | 단위 테스트 (DB 없음): 이체 규칙, 락 순서, 재시도 정책, 멱등성 호출 순서 |
| `TransferConcurrencyTest` | 같은 계좌에서 100건 동시 이체해도 성공한 이체가 하나도 유실되지 않음 |
| `TransferDeadlockTest` | A→B, B→A 동시 이체에서 데드락 없음 |
| `TransferIdempotencyTest` | 같은 키 10건 동시 요청 → 이체 1번, 나머지 9건은 첫 결과 그대로 |

## 패키지 구조

```
com.kyongseo.banktransfer
├── account       계좌 엔티티(잔액 규칙), 생성/조회 API
├── transfer      이체 API, 방식 선택(TransferService), 비관적/낙관적 락 구현
├── idempotency   Idempotency-Key 저장과 재전송 처리
└── global        공통 예외와 에러 응답
```
