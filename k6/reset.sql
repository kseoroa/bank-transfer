-- k6 부하 테스트용 계좌 초기화.
-- 계좌번호가 'K6'로 시작하는 계좌만 지우고 다시 만든다. (개발 중 만든 다른 계좌는 건드리지 않음)
--
-- 시나리오 1 (hot)    : 보내는 계좌 K6H000000000 1개 (100억 원) + 받는 계좌 K6H000000001 ~ K6H000000100 (0원)
-- 시나리오 2 (spread) : K6S000000001 ~ K6S000001000 1,000개 (각 1억 원)
--
-- 실행: docker exec -i bank-mysql mysql -ubank -pbank1234 bank < k6/reset.sql

SET SESSION cte_max_recursion_depth = 10000;

DELETE FROM account WHERE account_number LIKE 'K6%';

INSERT INTO account (account_number, owner_name, balance, version)
VALUES ('K6H000000000', 'k6-hot-sender', 10000000000, 0);

INSERT INTO account (account_number, owner_name, balance, version)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100)
SELECT CONCAT('K6H', LPAD(n, 9, '0')), CONCAT('k6-hot-receiver-', n), 0, 0 FROM seq;

INSERT INTO account (account_number, owner_name, balance, version)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT CONCAT('K6S', LPAD(n, 9, '0')), CONCAT('k6-spread-', n), 100000000, 0 FROM seq;

SELECT
    SUM(account_number LIKE 'K6H%') AS hot_accounts,
    SUM(account_number LIKE 'K6S%') AS spread_accounts
FROM account
WHERE account_number LIKE 'K6%';
