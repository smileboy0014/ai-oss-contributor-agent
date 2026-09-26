package com.ossagent.repository.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RepositorySource} 의 테스트 대역.
 *
 * <p>대체 수단은 아직 미결이다(Q-9). 정해지기 전까지는 자체 페이크를 쓴다 —
 * {@code .claude/rules/conventions/testing-philosophy.md}. 능력 인터페이스가 domain 에 있어
 * 페이크를 만들기 쉬운 것이 규율 ③ 의 실질적 이득이다.
 *
 * <p>Q-9 가 WireMock·녹화 응답으로 닫히면 교체 범위는 {@code src/test} 안에 갇힌다.
 *
 * <p>이 클래스를 쓰는 곳은 #7(규약 수집)·#15(저장소 분석)의 UseCase 테스트다.
 * 지금은 계약이 실제로 페이크로 대체 가능한지를 증명하는 것이 역할이다.
 */
@FakeAdapter
public class FakeRepositorySource implements RepositorySource {

    private final Map<String, RepositoryMetadata> metadata = new HashMap<>();
    private final Map<String, RepositoryFile> files = new HashMap<>();
    private final Map<String, RepositoryTree> trees = new HashMap<>();
    private final Map<String, RuntimeException> fileFailures = new HashMap<>();
    private final List<String> fetchedPaths = new ArrayList<>();
    private final List<String> fetchedTreeRefs = new ArrayList<>();
    private RuntimeException failure;

    public FakeRepositorySource given(RepositoryMetadata value) {
        metadata.put(value.coordinates().fullName(), value);
        return this;
    }

    public FakeRepositorySource givenFile(RepositoryCoordinates coordinates, String path,
            String content) {
        files.put(key(coordinates, path), new RepositoryFile(path, content));
        return this;
    }

    /** 다음 호출부터 이 예외를 던진다 — 레이트리밋·권한 오류 경로를 재현할 때 쓴다. */
    public FakeRepositorySource failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /**
     * <b>이 경로 하나만</b> 실패시킨다 — {@link #failWith} 는 전역이라 「파일 하나가 실패해도
     * 나머지는 계속 모은다」를 재현할 수 없다.
     *
     * <p>🔴 실패 모드를 재현하지 못하는 페이크는 게이트를 검증하지 못한다 —
     * {@code testing-philosophy.md}. 「항상 성공만 반환하는 페이크」가 정확히 그 문제다.
     */
    public FakeRepositorySource failFileWith(String path, RuntimeException exception) {
        fileFailures.put(path, exception);
        return this;
    }

    /** 실제로 조회된 경로. 「저장소 전체를 넘기지 않는다」(PRD §12)를 테스트로 확인할 때 쓴다. */
    public List<String> fetchedPaths() {
        return List.copyOf(fetchedPaths);
    }

    /**
     * 트리를 등록한다 — 경로만 주면 전부 blob 으로 만든다.
     *
     * <p>#15 선별 테스트가 쓰는 주 진입점이다. 시크릿 경로({@code .env} 등)를 섞어 두고
     * <b>그 경로가 {@link #fetchedPaths()} 에 나타나지 않는 것</b>으로 S-4 배선을 단언한다.
     */
    public FakeRepositorySource givenTree(RepositoryCoordinates coordinates, String ref,
            String... paths) {
        List<RepositoryTreeEntry> entries = new ArrayList<>(paths.length);
        for (String path : paths) {
            entries.add(new RepositoryTreeEntry(path, RepositoryTreeEntry.EntryType.BLOB, 0));
        }
        trees.put(key(coordinates, ref), new RepositoryTree("fake-tree-sha", entries, false));
        return this;
    }

    /** 트리 자체를 주입한다 — {@code truncated} · 서브모듈 같은 모양을 재현할 때 쓴다. */
    public FakeRepositorySource givenTree(RepositoryCoordinates coordinates, String ref,
            RepositoryTree tree) {
        trees.put(key(coordinates, ref), tree);
        return this;
    }

    /** 트리를 조회한 {@code ref}. 「저장소당 1회」(NFR-1)를 단언할 때 쓴다. */
    public List<String> fetchedTreeRefs() {
        return List.copyOf(fetchedTreeRefs);
    }

    /**
     * ⚠️ 컨텍스트가 테스트 클래스 사이에 캐시되고 이 대역은 싱글턴이다.
     * <b>누적되는 기록</b>({@link #fetchedPaths()}·{@link #fetchedTreeRefs()})을 단언하는
     * 테스트는 {@code @BeforeEach} 에서 이것을 부른다 — {@code testing-philosophy.md}.
     */
    public FakeRepositorySource reset() {
        metadata.clear();
        files.clear();
        trees.clear();
        fileFailures.clear();
        fetchedPaths.clear();
        fetchedTreeRefs.clear();
        failure = null;
        return this;
    }

    /**
     * ⚠️ 미등록 저장소에서 던지는 {@link IllegalStateException} 은 <b>「저장소가 없다」가 아니라
     * 「테스트 셋업이 빠졌다」</b>는 뜻이다. 실제 구현은 그 경우 다른 예외를 던진다.
     *
     * <p>일부러 타입을 다르게 둔다. 페이크가 실제와 같은 예외를 던지면 <b>등록을 깜빡한 테스트가
     * 「저장소 없음 시나리오를 검증한 테스트」로 통과</b>해 버린다.
     * 「없음」·레이트리밋·권한 시나리오를 검증하려면 {@link #failWith(RuntimeException)} 으로
     * 실제 예외를 주입한다.
     */
    @Override
    public RepositoryMetadata fetchMetadata(RepositoryCoordinates coordinates) {
        throwIfFailing();
        RepositoryMetadata found = metadata.get(coordinates.fullName());
        if (found == null) {
            throw new IllegalStateException(
                    "페이크에 등록되지 않은 저장소입니다(테스트 셋업 오류): " + coordinates.fullName());
        }
        return found;
    }

    @Override
    public Optional<RepositoryFile> fetchFile(RepositoryCoordinates coordinates, String path,
            String ref) {
        throwIfFailing();
        fetchedPaths.add(path);
        RuntimeException perPath = fileFailures.get(path);
        if (perPath != null) {
            throw perPath;
        }
        // 🔴 없는 파일만 빈 값이다. 실패는 failWith·failFileWith 로 주입한다 —
        //    S-5 의 계약을 페이크도 지킨다
        return Optional.ofNullable(files.get(key(coordinates, path)));
    }

    /**
     * 🔴 <b>빈 트리를 돌려주지 않는다.</b> 등록이 없으면 셋업 오류다 —
     * {@code fetchMetadata} 와 같은 이유로 실제 구현과 다른 예외 타입을 쓴다.
     * 여기서 빈 트리를 주면 「선별 0건」이 정상 통과로 읽혀 가드가 공허해진다.
     */
    @Override
    public RepositoryTree fetchTree(RepositoryCoordinates coordinates, String ref) {
        throwIfFailing();
        fetchedTreeRefs.add(ref);
        RepositoryTree found = trees.get(key(coordinates, ref));
        if (found == null) {
            throw new IllegalStateException(
                    "페이크에 등록되지 않은 트리입니다(테스트 셋업 오류): %s@%s"
                            .formatted(coordinates.fullName(), ref));
        }
        return found;
    }

    private void throwIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }

    private static String key(RepositoryCoordinates coordinates, String path) {
        return coordinates.fullName() + ":" + path;
    }
}
