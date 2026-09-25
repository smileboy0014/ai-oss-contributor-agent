-- 이슈 증분 수집 커서 — #8
--
-- 왜 last_scanned_at 을 재사용하지 않는가:
--   last_scanned_at 은 「우리가 언제 돌렸나」이고 커서는 「데이터를 어디까지 봤나」다.
--   겹쳐 쓰면 스캔이 실패해도 커서가 전진해 이슈를 영구히 건너뛴다.
--
-- 왜 issue 테이블에서 MAX(github_updated_at) 으로 파생하지 않는가:
--   커서가 행 보존 정책에 묶인다. 오래된 이슈를 정리·아카이브하는 순간 MAX 가 뒤로
--   점프해 전량 재스캔이 터진다 — 커서를 지우지도 않았는데 리밋을 통째로 태운다.
--
-- ⚠ H2·PostgreSQL 공통 문법만 쓴다 (Q-2). 벤더 고유 문법 금지.

-- 다음 이슈 조회의 since 값 — 데이터 워터마크. 경계는 포함(inclusive)이다.
-- 배타로 잡으면 같은 초에 갱신된 경계 이슈가 영구 누락된다 (GitHub updated_at 은 초 단위)
ALTER TABLE oss_repository
    ADD COLUMN issue_cursor_updated_at TIMESTAMP(6) WITH TIME ZONE;

-- page 1 응답의 ETag. 커서가 전진하면 NULL 로 버린다 — ETag 는 since·page 와 짝이다
ALTER TABLE oss_repository
    ADD COLUMN issue_cursor_etag VARCHAR(255);
