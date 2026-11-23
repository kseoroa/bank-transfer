#!/usr/bin/env python3
"""k6 실행 후 MySQL의 실제 잔액이 k6가 센 성공 건수와 맞는지 검증한다.

사용법: python3 k6/verify.py k6/results/hot-PESSIMISTIC.json
결과(통과 여부와 실제 값)를 같은 JSON 파일에 balanceCheck 필드로 추가한다.

검증 기준
  hot    : 보내는 계좌 = 100억 - 성공 x 1,000 / 받는 계좌 합계 = 성공 x 1,000 / version 합계 = 성공 x 2
  spread : 1,000개 계좌 총액 = 1,000억 (보존) / version 합계 = 성공 x 2 / 음수 잔액 없음
  (이체 1건이 커밋되면 두 계좌의 version이 1씩 오르므로, version 합계로 "실제 커밋된 이체 수"를 알 수 있다)
"""
import json
import subprocess
import sys

AMOUNT = 1000
HOT_SENDER_INITIAL = 10_000_000_000
SPREAD_COUNT = 1000
SPREAD_INITIAL = 100_000_000
MYSQL = ["docker", "exec", "-i", "bank-mysql", "mysql", "-ubank", "-pbank1234", "-N", "-B", "bank"]


def query(sql: str) -> list[int]:
    out = subprocess.run(MYSQL + ["-e", sql], capture_output=True, text=True, check=True).stdout
    return [int(v) for v in out.split()]


def verify_hot(success: int) -> dict:
    sender, receivers, versions = query(
        "SELECT"
        " (SELECT balance FROM account WHERE account_number = 'K6H000000000'),"
        " (SELECT COALESCE(SUM(balance), 0) FROM account"
        "   WHERE account_number LIKE 'K6H%' AND account_number <> 'K6H000000000'),"
        " (SELECT COALESCE(SUM(version), 0) FROM account WHERE account_number LIKE 'K6H%')"
    )
    checks = {
        "보내는 계좌": (HOT_SENDER_INITIAL - success * AMOUNT, sender),
        "받는 계좌 합계": (success * AMOUNT, receivers),
        "version 합계": (success * 2, versions),
    }
    return checks


def verify_spread(success: int) -> dict:
    total, versions, negatives = query(
        "SELECT COALESCE(SUM(balance), 0), COALESCE(SUM(version), 0), COALESCE(SUM(balance < 0), 0)"
        " FROM account WHERE account_number LIKE 'K6S%'"
    )
    checks = {
        "총액(보존)": (SPREAD_COUNT * SPREAD_INITIAL, total),
        "version 합계": (success * 2, versions),
        "음수 잔액 계좌 수": (0, negatives),
    }
    return checks


def main() -> None:
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        result = json.load(f)

    success = result["success"]
    checks = verify_hot(success) if result["scenario"] == "hot" else verify_spread(success)
    passed = all(expected == actual for expected, actual in checks.values())

    print(f"----- 최종 잔액 검증 [{result['scenario']} / {result['lock']}] (k6 성공 {success:,}건 기준) -----")
    for name, (expected, actual) in checks.items():
        mark = "OK " if expected == actual else "MISMATCH"
        print(f"  {mark} {name:<12} 기대 {expected:>18,}  실제 {actual:>18,}")
    print(f"  => {'통과' if passed else '불일치'}")

    result["balanceCheck"] = {
        "passed": passed,
        "details": {name: {"expected": e, "actual": a} for name, (e, a) in checks.items()},
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
