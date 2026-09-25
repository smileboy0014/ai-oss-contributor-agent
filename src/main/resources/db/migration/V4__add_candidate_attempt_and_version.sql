-- 후보 상태머신(#12)이 필요로 하는 두 컬럼.
--
-- ⚠ H2·PostgreSQL 공통 문법만 쓴다 — Q-2. ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT
--   는 양쪽에서 동작한다.
--
-- ⚠ DEFAULT 가 필요한 이유는 NOT NULL 컬럼을 추가할 때 기존 행을 채워야 하기 때문이다.
--   (ddl-auto: validate 는 nullability 만 보고 DEFAULT 를 요구하지 않는다.)
--   지금은 행이 없지만 운영 DB 를 쌓기 시작하면 필수다.

-- CODE → VERIFY → REVIEW 한 바퀴를 센다 — Q-6 확정 (2026-09-25).
-- 0 = 아직 구현에 착수하지 않음. 첫 IMPLEMENTING 진입에서 1 이 된다.
-- 상한은 agent.execution.max-retries(3)이고, 도메인이 그보다 큰 값을 거부한다 — 불변식 ⑧.
ALTER TABLE contribution_candidate
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0;

-- 낙관적 락. 상태머신이 이 제품의 유일한 중복 실행 방어다.
-- 이것이 없으면 트랜잭션 둘이 동시에 SELECTED 를 읽고 둘 다 전이에 성공해
-- 같은 후보에 구현 사이클이 2개 생긴다 — 30분 샌드박스 ×2, LLM 과금 ×2, 브랜치 2개.
ALTER TABLE contribution_candidate
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
