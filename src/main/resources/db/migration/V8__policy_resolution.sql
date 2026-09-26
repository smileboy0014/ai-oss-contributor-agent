-- 이슈 #24 — Q-8 보류를 사람이 해소한 흔적
--
-- ⚠ 머지 순서: V7(#9 이슈 필터) → V8(여기). baseline-on-migrate 가 false 라 순서가
--   역전되면 Flyway 가 out-of-order 로 보고 validate 에서 기동을 막는다.
--   H2 는 매번 새로 떠서 드러나지 않고 PostgreSQL 개발 DB 에서만 터진다 — Q-2b.
--
-- ⚠ 벤더 고유 문법을 쓰지 않는다 (Q-2). H2·PostgreSQL 양쪽에서 같은 SQL 이 돈다.

-- ─────────────────────────────────────────────────────────────
-- 🔴 왜 컬럼이 필요한가 — 로그로는 판정의 출처가 사라진다
-- ─────────────────────────────────────────────────────────────
-- 해소 사실을 로그로만 남기면 ai_contribution_allowed = TRUE 가
-- 「LLM 이 문서를 읽고 판정한 것」인지 「사람이 보류를 풀어 준 것」인지
-- DB 에서 구분되지 않는다. 하류(#18·#23)는 둘을 똑같이 취급하고,
-- S-5 판정의 출처가 영구히 사라진다.
--
-- ⚠ 이것은 #25 의 「승인 기록 테이블」과 다른 물건이다. 저쪽은 누가 언제 무엇을
--   승인했는지의 이력이고, 여기는 이 정책 행 하나의 출처 표시다.

-- 비어 있지 않으면 「사람이 판단했다」는 뜻이다.
-- 🔴 재분석이 이 판단을 허용 방향으로 덮어쓰지 못하게 하는 근거가 된다 (Q-8).
--   다만 금지 방향으로 조이는 것은 막지 않는다 — 막으면 대상 저장소가 규약을
--   AI 기여 금지로 바꿔도 따라가지 못해 S-5 가 깨진다
ALTER TABLE repository_policy
    ADD COLUMN resolved_at TIMESTAMP(6) WITH TIME ZONE;

-- 사람이 쓴 판단 근거.
--
-- 🔴 pending_reason 과 다른 정보다 — 저쪽은 「기계가 기록한 보류 원인」(어느 경로를
--   왜 못 읽었는가)이고 이쪽은 「사람이 무엇을 보고 그렇게 판단했는가」다.
--   그래서 해소해도 pending_reason 을 비우지 않는다.
--
-- ⚠ TEXT 가 아니라 VARCHAR 다. 대입 지점(resolvePending)에서 TokenRedactor 를 거치므로
--   외부 텍스트가 아니고, 따라서 @ExternalText 마커도 등록표 행도 필요 없다 —
--   repository_policy.pending_reason(V5)과 같은 취급이다.
--   ⚠ 입력 상한은 1000자이고 컬럼이 1024인 것은 마스킹이 길이를 늘릴 수 있어서다
ALTER TABLE repository_policy
    ADD COLUMN resolution_note VARCHAR(1024);
