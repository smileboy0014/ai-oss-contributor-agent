package com.ossagent.repository.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PolicyDocumentSource} 의 테스트 대역.
 *
 * <p>경로별로 결과를 지정한다. 지정하지 않은 경로는 {@link DocumentFetchOutcome#ABSENT} —
 * 「그 문서가 없다」가 기본값인 것이 실제와 맞다. 대부분의 저장소는 후보 13경로 중 두셋만 갖는다.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있다.</b> 「항상 성공만 반환하는 페이크」는 S-5 게이트를
 * 검증하지 못한다 — 이 이슈에서 가장 중요한 것이 「못 읽었을 때 어떻게 되는가」다.
 */
@FakeAdapter
public class FakePolicyDocumentSource implements PolicyDocumentSource {

    private final Map<String, FetchedDocument> scripted = new HashMap<>();
    private final List<RepositoryCoordinates> calls = new ArrayList<>();

    public FakePolicyDocumentSource givenRead(String path, String content) {
        scripted.put(path, FetchedDocument.read(pathOf(path), content));
        return this;
    }

    public FakePolicyDocumentSource givenAbsent(String path) {
        scripted.put(path, FetchedDocument.absent(pathOf(path)));
        return this;
    }

    /**
     * 일시적이든 영구적이든 — {@link UnreadableReason} 이 그것을 가른다.
     *
     * <p>🔴 <b>지문이 없다.</b> 응답 자체를 받지 못한 경우(5xx·레이트리밋)를 재현한다 —
     * 이때는 규약이 바뀌었는지 알 방법이 없다 (#68 FR-5).
     */
    public FakePolicyDocumentSource givenUnreadable(String path, UnreadableReason reason) {
        scripted.put(path, FetchedDocument.unreadable(pathOf(path), reason));
        return this;
    }

    /**
     * 🔴 못 읽었지만 <b>지문은 있다</b> — 응답은 받았으나 판정에 쓸 수 없는 경우 (#68).
     *
     * <p>{@code TRUNCATED} 가 대표적이다. 이것이 없으면 「바뀌었는데 못 읽는다」는
     * 이슈 #68 의 핵심 시나리오를 <b>테스트로 재현할 수 없다.</b>
     *
     * @param contentSeed 지문 계산에만 쓰인다. 값이 달라지면 지문이 달라진다
     */
    public FakePolicyDocumentSource givenUnreadableWithFingerprint(String path,
            UnreadableReason reason, String contentSeed) {
        scripted.put(path, FetchedDocument.unreadable(pathOf(path), reason,
                DocumentFingerprint.of(contentSeed)));
        return this;
    }

    public List<RepositoryCoordinates> calls() {
        return List.copyOf(calls);
    }

    /** 싱글턴이라 앞 테스트의 흔적이 남는다. */
    public FakePolicyDocumentSource reset() {
        scripted.clear();
        calls.clear();
        return this;
    }

    @Override
    public RepositoryDocuments collect(RepositoryCoordinates coordinates,
            List<PolicyDocumentPath> paths) {
        calls.add(coordinates);
        List<FetchedDocument> results = new ArrayList<>(paths.size());
        for (PolicyDocumentPath path : paths) {
            FetchedDocument scriptedDoc = scripted.get(path.path());
            // 지정하지 않은 경로는 「없다」 — 실제 저장소가 그렇다
            results.add(scriptedDoc != null
                    ? new FetchedDocument(path, scriptedDoc.outcome(), scriptedDoc.content(),
                            scriptedDoc.reason(), scriptedDoc.fingerprint())
                    : FetchedDocument.absent(path));
        }
        return new RepositoryDocuments(results);
    }

    /** 스크립트 등록용 — 역할은 실제 호출 시 넘어온 것으로 덮어쓴다. */
    private static PolicyDocumentPath pathOf(String path) {
        return new PolicyDocumentPath(path, PolicyDocumentPath.Role.REQUIRED);
    }
}
