#!/usr/bin/env bash
# 조합 하나 실행: 계좌 초기화 → k6 부하 → MySQL 잔액 검증
#
# 사용법: k6/run.sh <hot|spread> <PESSIMISTIC|OPTIMISTIC>
# 환경변수: BASE_URL(기본 http://localhost:8080) VUS(기본 50) DURATION(기본 30s) RESULT_DIR(기본 k6/results)
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO=${1:?"사용법: k6/run.sh <hot|spread> <PESSIMISTIC|OPTIMISTIC>"}
LOCK=${2:?"사용법: k6/run.sh <hot|spread> <PESSIMISTIC|OPTIMISTIC>"}
BASE_URL=${BASE_URL:-http://localhost:8080}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}
RESULT_DIR=${RESULT_DIR:-k6/results}
SUMMARY_FILE="$RESULT_DIR/${SCENARIO}-${LOCK}.json"
mkdir -p "$RESULT_DIR"

echo "[1/3] k6 계좌 초기화 (k6/reset.sql)"
docker exec -i bank-mysql mysql -ubank -pbank1234 bank < k6/reset.sql 2>&1 | grep -v 'Using a password'

echo "[2/3] k6 실행: scenario=$SCENARIO lock=$LOCK vus=$VUS duration=$DURATION url=$BASE_URL"
k6 run --quiet \
    -e SCENARIO="$SCENARIO" -e LOCK="$LOCK" -e BASE_URL="$BASE_URL" \
    -e VUS="$VUS" -e DURATION="$DURATION" -e SUMMARY_FILE="$SUMMARY_FILE" \
    k6/transfer.js

if [[ ! -f "$SUMMARY_FILE" ]]; then
    echo "결과 파일이 없습니다: $SUMMARY_FILE"
    echo "(snap으로 설치한 k6는 홈 디렉터리 밖(/tmp 등)에 쓸 수 없습니다. RESULT_DIR을 프로젝트 안으로 지정하세요.)"
    exit 1
fi

echo "[3/3] 최종 잔액 검증"
python3 k6/verify.py "$SUMMARY_FILE"
