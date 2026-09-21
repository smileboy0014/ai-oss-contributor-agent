package com.ossagent.repository.domain;

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
public class FakeRepositorySource implements RepositorySource {

    private final Map<String, RepositoryMetadata> metadata = new HashMap<>();
    private final Map<String, RepositoryFile> files = new HashMap<>();
    private final List<String> fetchedPaths = new ArrayList<>();
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

    /** 실제로 조회된 경로. 「저장소 전체를 넘기지 않는다」(PRD §12)를 테스트로 확인할 때 쓴다. */
    public List<String> fetchedPaths() {
        return List.copyOf(fetchedPaths);
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
        // 🔴 없는 파일만 빈 값이다. 실패는 failWith 로 주입한다 — S-5 의 계약을 페이크도 지킨다
        return Optional.ofNullable(files.get(key(coordinates, path)));
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
