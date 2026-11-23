#!/usr/bin/env bash
# 2개 시나리오 x 2개 락 방식을 모두 실행하고 비교표를 출력한다.
#
# 사용법: k6/run-all.sh
# 환경변수: BASE_URL VUS DURATION (run.sh와 같음), WARMUP(기본 10s, 0이면 생략)
#           RESULT_DIR(기본 k6/results/<날짜-시각>)
set -uo pipefail
cd "$(dirname "$0")/.."

export BASE_URL=${BASE_URL:-http://localhost:8080}
export VUS=${VUS:-50}
export DURATION=${DURATION:-30s}
export RESULT_DIR=${RESULT_DIR:-k6/results/$(date +%Y%m%d-%H%M%S)}
WARMUP=${WARMUP:-10s}
mkdir -p "$RESULT_DIR"

if [[ "$WARMUP" != "0" ]]; then
    echo "===== 워밍업 (JIT, 커넥션 풀 예열 / 결과는 비교표에서 제외) ====="
    docker exec -i bank-mysql mysql -ubank -pbank1234 bank < k6/reset.sql 2>&1 | grep -v 'Using a password' > /dev/null
    for LOCK in PESSIMISTIC OPTIMISTIC; do
        k6 run --quiet -e SCENARIO=spread -e LOCK="$LOCK" -e BASE_URL="$BASE_URL" -e VUS="$VUS" \
            -e DURATION="$WARMUP" -e SUMMARY_FILE="$RESULT_DIR/_warmup-$LOCK.json" k6/transfer.js > /dev/null
    done
fi

for SCENARIO in hot spread; do
    for LOCK in PESSIMISTIC OPTIMISTIC; do
        echo
        echo "################ $SCENARIO / $LOCK ################"
        k6/run.sh "$SCENARIO" "$LOCK" || echo "!! $SCENARIO / $LOCK 실행 또는 검증 실패"
    done
done

echo
python3 k6/report.py "$RESULT_DIR"
