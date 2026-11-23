# k6 부하 테스트: 비관적 락 vs 낙관적 락

## 구성

| 파일 | 역할 |
|---|---|
| `reset.sql` | `K6`로 시작하는 계좌만 지우고 다시 만든다 (다른 개발용 계좌는 건드리지 않음) |
| `transfer.js` | k6 스크립트. `SCENARIO`(hot/spread), `LOCK`(PESSIMISTIC/OPTIMISTIC)을 환경변수로 받음 |
| `verify.py` | 실행 후 MySQL 실제 잔액이 k6가 센 성공 건수와 맞는지 검증 |
| `run.sh` | 조합 1개: 초기화 → k6 → 검증 |
| `run-all.sh` | 워밍업 후 4개 조합 전부 실행 → 비교표 출력 |
| `report.py` | 결과 폴더의 JSON을 마크다운 표로 출력 |

### 시나리오

| 시나리오 | 계좌 | 요청 |
|---|---|---|
| `hot` (한 계좌에 집중) | 보내는 계좌 `K6H000000000` 1개(100억 원), 받는 계좌 100개(0원) | 매 요청 보내는 계좌 → 받는 계좌 100개 중 무작위, 1,000원 |
| `spread` (서로 다른 계좌) | `K6S000000001`~`K6S000001000` 1,000개(각 1억 원) | 매 요청 서로 다른 두 계좌를 무작위로 골라 1,000원 |

### 최종 잔액 검증 기준 (`verify.py`)

- **hot**: 보내는 계좌 = 100억 − 성공 × 1,000 / 받는 계좌 합계 = 성공 × 1,000 / version 합계 = 성공 × 2
- **spread**: 총액 = 1,000억 원(보존) / version 합계 = 성공 × 2 / 음수 잔액 없음
- 이체 1건이 커밋되면 두 계좌의 version이 1씩 오른다. 그래서 version 합계로 "실제 커밋된 이체 수"를 확인할 수 있다.

## k6 설치 (WSL Ubuntu)

이 개발 환경에는 snap으로 설치된 k6 v2.3.0이 이미 있다 (`which k6` → `/snap/bin/k6`). 새로 설치하려면 둘 중 하나를 쓴다.

```bash
# 방법 1: snap
sudo snap install k6

# 방법 2: 공식 apt 저장소
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

> snap 버전 k6는 샌드박스 때문에 홈 디렉터리 밖(`/tmp` 등)에 결과 파일을 쓸 수 없다. `RESULT_DIR`은 프로젝트 안으로 둔다(기본값이 그렇다).

## 실행 방법

```bash
# 0) MySQL / Redis
docker compose up -d

# 1) 앱을 loadtest 프로필로 실행 (SQL 콘솔 출력 OFF)
./gradlew bootJar
java -jar build/libs/bank-transfer-0.0.1-SNAPSHOT.jar --spring.profiles.active=loadtest
#   또는: ./gradlew bootRun --args='--spring.profiles.active=loadtest'
#   8080이 사용 중이면 --server.port=18080 을 붙이고 아래에서 BASE_URL을 맞춘다.

# 2) 전체 비교 (워밍업 10초 + 4개 조합 x 30초, 약 2분 30초)
k6/run-all.sh
BASE_URL=http://localhost:18080 VUS=50 DURATION=30s k6/run-all.sh   # 옵션 지정 예

# 조합 하나만
k6/run.sh hot PESSIMISTIC
k6/run.sh spread OPTIMISTIC

# 계좌 초기화만
docker exec -i bank-mysql mysql -ubank -pbank1234 bank < k6/reset.sql

# 저장된 결과를 다시 표로 보기
python3 k6/report.py k6/results/<폴더>
```

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 서버 주소 |
| `VUS` | `50` | 동시 가상 사용자 (`constant-vus`: 응답을 받으면 바로 다음 요청) |
| `DURATION` | `30s` | 조합당 실행 시간 |
| `WARMUP` | `10s` | `run-all.sh` 워밍업 시간 (`0`이면 생략, 결과는 표에서 제외) |
| `RESULT_DIR` | `run.sh`: `k6/results`, `run-all.sh`: `k6/results/<날짜-시각>` | 결과 JSON 위치 (`.gitignore` 처리됨) |

낙관적 락 재시도 횟수는 앱 설정 `bank.transfer.optimistic.max-attempts`(기본 5)를 따른다. 앱을 띄울 때 `--bank.transfer.optimistic.max-attempts=30`처럼 바꿀 수 있다.

## 측정 결과 (2026-11-24)

**조건**
- 부하: VU 50명, 조합당 30초, 이체 1건 1,000원, 워밍업 10초
- 앱: Hikari 커넥션 풀 기본값(10), 낙관적 락 최대 5번 시도
- 환경: WSL2 Ubuntu 22.04(8코어, 7GB), Docker MySQL 8.0.46(REPEATABLE-READ, `innodb_flush_log_at_trx_commit=1`)
- k6, 앱, MySQL이 모두 한 장비에서 돌았다. 측정 중 load average는 5~7이었다.

같은 조건을 3회 반복했고, 아래 숫자는 모두 `k6/results/run1~3` JSON에 저장된 실측값이다. 4개 조합 × 3회 = 12번 모두 최종 잔액 검증을 통과했다.

### 시나리오 1: 한 계좌에 집중 (hot)

| 방식 | 회차 | 요청 | 성공 | 실패 (재시도 소진) | 성공 TPS | 전체 TPS | p95 (ms) | 실패율 | 최종 잔액 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| PESSIMISTIC | 1 | 1,665 | 1,665 | 0 | **54.0** | 54.0 | 1,019.1 | 0.00% | ✅ 일치 |
| PESSIMISTIC | 2 | 978 | 978 | 0 | **31.5** | 31.5 | 2,398.9 | 0.00% | ✅ 일치 |
| PESSIMISTIC | 3 | 1,418 | 1,418 | 0 | **45.8** | 45.8 | 1,350.2 | 0.00% | ✅ 일치 |
| OPTIMISTIC | 1 | 2,826 | 1,184 | 1,642 | **38.5** | 91.9 | 781.1 | 58.10% | ✅ 일치 |
| OPTIMISTIC | 2 | 1,667 | 669 | 998 | **21.7** | 54.2 | 1,407.4 | 59.87% | ✅ 일치 |
| OPTIMISTIC | 3 | 2,502 | 1,036 | 1,466 | **34.2** | 82.5 | 923.9 | 58.59% | ✅ 일치 |

### 시나리오 2: 서로 다른 계좌 (spread)

| 방식 | 회차 | 요청 | 성공 | 실패 | 성공 TPS | p95 (ms) | 실패율 | 최종 잔액 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| PESSIMISTIC | 1 | 5,794 | 5,794 | 0 | 192.4 | 448.6 | 0.00% | ✅ 일치 |
| PESSIMISTIC | 2 | 4,739 | 4,739 | 0 | 157.0 | 555.4 | 0.00% | ✅ 일치 |
| PESSIMISTIC | 3 | 8,533 | 8,533 | 0 | 283.6 | 233.5 | 0.00% | ✅ 일치 |
| OPTIMISTIC | 1 | 6,258 | 6,258 | 0 | 207.0 | 454.0 | 0.00% | ✅ 일치 |
| OPTIMISTIC | 2 | 6,674 | 6,674 | 0 | 220.6 | 406.5 | 0.00% | ✅ 일치 |
| OPTIMISTIC | 3 | 8,123 | 8,123 | 0 | 267.8 | 300.1 | 0.00% | ✅ 일치 |

### 해석

- **hot 시나리오는 3회 모두 비관적 락이 성공 TPS가 높았다.** 회차별로 54.0 대 38.5, 31.5 대 21.7, 45.8 대 34.2다. 낙관적 락은 매번 요청의 58~60%가 재시도 소진(`TRANSFER_CONFLICT`)으로 실패했다.
- **낙관적 락의 "전체 TPS"와 p95가 더 좋아 보이는 것은 착시다.** 실패 응답이 빨리 돌아와서 요청 수가 늘고 응답 시간이 짧아질 뿐이다. 실제로 처리된 이체는 성공 TPS로 비교해야 한다.
- **spread 시나리오에서는 낙관적 락도 충돌 실패가 0건이었다.** 성공 TPS의 우열은 회차마다 뒤집혔다(1·2회차는 낙관적 락, 3회차는 비관적 락이 높음). 같은 방식 안에서도 회차 간 차이(비관적 락 157~284)가 방식 간 차이보다 커서, 이 측정으로는 **어느 쪽이 빠르다고 말할 수 없다.**
- **spread에서도 TPS가 200~280 수준에서 멈춘 이유는 락이 아니라 다른 병목으로 보인다.** 커넥션 풀 10개, 커밋마다 fsync, 한 장비에서 k6·앱·DB를 함께 돌린 환경이 원인일 가능성이 높다. VU 50명이 커넥션 10개를 나눠 쓰므로 응답 시간의 상당 부분은 커넥션 대기다(검증하지 않은 추정).
- **회차 간 편차가 크다** (hot 비관적 락 31.5~54.0 TPS). 숫자 자체보다 "같은 회차 안에서 두 방식의 상대 비교"로 읽어야 한다. 더 정확히 비교하려면 k6를 다른 장비에서 돌리고, 회차를 늘리고, 커넥션 풀 크기를 바꿔 가며 측정하면 된다.
