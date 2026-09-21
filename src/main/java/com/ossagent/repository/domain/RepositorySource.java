package com.ossagent.repository.domain;

import java.util.Optional;

/**
 * 대상 저장소의 메타데이터와 파일을 읽어 오는 <b>능력</b>.
 *
 * <p>이름이 {@code GitHubClient} 가 아니라 {@code RepositorySource} 인 이유 —
 * domain 은 「무엇을 할 수 있어야 하는가」만 알고, 그것이 GitHub 인지 GitLab 인지는 모른다.
 * 기술 이름은 구현체({@code GitHubRepositorySource})에만 나타난다 —
 * {@code .claude/rules/conventions/architecture.md} 규율 ③.
 *
 * <p>🔴 <b>읽기만 있다.</b> Fork 생성·push·PR 생성은 이 능력에 없다. 원본으로 가는 경로는
 * 읽기만 존재한다 — {@code safety-boundaries.md} S-1. 쓰기 능력은 별도 인터페이스
 * ({@code ForkRegistry} · {@code DraftPrPublisher}, 이슈 #22 · #23)로 분리하고,
 * 거기에 Fork owner 어설션을 둔다.
 *
 * <p><b>구현은 트랜잭션 밖에서 호출한다.</b> 대외 호출 지연이 DB 커넥션·락 점유로 번진다 —
 * {@code .claude/rules/context/external-deps.md}.
 */
public interface RepositorySource {

    /**
     * 저장소 메타데이터를 읽는다.
     *
     * <p>조회 실패는 런타임 예외로 전파된다. 실패의 <b>종류</b>(레이트리밋 · 권한 · 없음)를
     * 구분하는 것은 구현의 관심사이고, domain 은 그 타입을 알지 않는다 — 규율 ①.
     */
    RepositoryMetadata fetchMetadata(RepositoryCoordinates coordinates);

    /**
     * 저장소의 파일 하나를 읽는다.
     *
     * <p>🔴 <b>{@link Optional#empty()} 는 「그 경로에 파일이 없다」는 뜻 하나뿐이다.</b>
     * 그 밖의 모든 실패 — 권한 · 레이트리밋 · 1MB 초과로 내용을 받지 못함 · 경로가 디렉터리나
     * 심볼릭링크 — 는 <b>예외로 전파한다.</b>
     *
     * <p>이 계약이 S-5 다. 규약 판정에서 「읽지 못했다」가 「규약이 없다」로 번역되면 규약 위반 PR 이
     * 나간다. {@code safety-boundaries.md} S-5 는 「정책 파싱 실패는 「허용」이 아니라 「보류」다」라고
     * 못 박는다. 빈 {@link Optional} 은 호출자에게 「허용」으로 읽히므로, 보류해야 할 상황을
     * 빈 값으로 돌려주지 않는다.
     *
     * <p>「{@code CONTRIBUTING.md} 가 없는 저장소」는 오류가 아니라 정상 상황이고,
     * 규약 판정은 그 사실 자체를 입력으로 쓴다(이슈 #7).
     *
     * @param ref 브랜치 · 태그 · 커밋 SHA. {@code null} 이면 기본 브랜치를 본다.
     *            특정 시점의 파일을 봐야 하는 단계(이슈 #15 저장소 분석)가 이 시그니처를
     *            다시 열지 않도록 지금 둔다
     */
    Optional<RepositoryFile> fetchFile(RepositoryCoordinates coordinates, String path, String ref);
}
