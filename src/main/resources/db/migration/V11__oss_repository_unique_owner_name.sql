-- #114 — 같은 대상 저장소를 URL 철자만 다르게 두 번 등록하지 못하게 한다.
--
-- 🔴 지금까지 UNIQUE 는 url 뿐이었다. `…/spring-kafka` 와 `…/spring-kafka.git` 로 두 번 등록하면
--    행 둘 → 커서 둘 → 이슈 행 둘(UNIQUE 가 (repository_id, github_issue_number)) → 후보 둘 →
--    **같은 upstream 이슈에 Draft PR 두 개**. uk_pull_request_candidate 는 그것을 막지 못한다.
--
-- ⚠ H2·PostgreSQL 공통 문법만 쓴다 (Q-2b-1). 이미 중복이 있는 DB 에서는 이 마이그레이션이
--    실패한다 — 그것이 맞다. 조용히 한쪽을 지우지 않는다.
ALTER TABLE oss_repository
    ADD CONSTRAINT uk_oss_repository_owner_name UNIQUE (owner, name);
