#!/usr/bin/env python3
"""결과 폴더의 k6 JSON들을 마크다운 비교표로 출력한다. (측정값을 그대로 옮길 뿐 계산하지 않는다)

사용법: python3 k6/report.py k6/results/<날짜-시각>
"""
import json
import pathlib
import sys

ORDER = [("hot", "PESSIMISTIC"), ("hot", "OPTIMISTIC"), ("spread", "PESSIMISTIC"), ("spread", "OPTIMISTIC")]
SCENARIO_LABEL = {"hot": "1. 한 계좌에 집중", "spread": "2. 서로 다른 계좌"}


def main() -> None:
    result_dir = pathlib.Path(sys.argv[1])
    results = {}
    for path in result_dir.glob("*.json"):
        if path.name.startswith("_"):
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        results[(data["scenario"], data["lock"])] = data

    if not results:
        print(f"{result_dir}에 결과가 없습니다.")
        return

    any_result = next(iter(results.values()))
    print(f"결과 폴더: {result_dir}  (VUs {any_result['vus']}, {any_result['duration']})")
    print()
    print("| 시나리오 | 방식 | 요청 | 성공 | 실패 (충돌/기타) | 성공 TPS | 전체 TPS | p95 (ms) | 실패율 | 최종 잔액 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for key in ORDER:
        r = results.get(key)
        if r is None:
            continue
        check = r.get("balanceCheck")
        balance = "검증 안 됨" if check is None else ("✅ 일치" if check["passed"] else "❌ 불일치")
        print(
            f"| {SCENARIO_LABEL[r['scenario']]} | {r['lock']} | {r['requests']:,} | {r['success']:,} "
            f"| {r['conflict'] + r['otherFail']:,} ({r['conflict']:,}/{r['otherFail']:,}) "
            f"| {r['successTps']:,} | {r['totalTps']:,} | {r['p95Ms']:,} | {r['failRate'] * 100:.2f}% | {balance} |"
        )


if __name__ == "__main__":
    main()
