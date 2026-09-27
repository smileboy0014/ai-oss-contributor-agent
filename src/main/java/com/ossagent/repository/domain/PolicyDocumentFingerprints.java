package com.ossagent.repository.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 규약 후보 경로별 지문 묶음 — 이슈 #68. <b>「규약이 바뀌었는가」의 비교 기준</b>이다.
 *
 * <h2>🔴 이 타입의 존재 이유 — 「없다」와 「달라졌다」를 섞지 않는다</h2>
 *
 * <p>두 상태를 하나의 {@code boolean} 으로 뭉치는 순간, <b>비교 기준이 없는 기존 정책 행</b>이
 * 「바뀌었다」로 읽힌다. 그러면 이 기능을 배포하는 순간 모든 저장소가 일괄 보류로 떨어진다.
 *
 * <table border="1">
 *   <caption>{@link #changedRequiredPaths} 가 세는 것</caption>
 *   <tr><td>양쪽에 지문이 있고 서로 <b>다르다</b></td><td>✅ <b>바뀌었다</b></td></tr>
 *   <tr><td>양쪽에 있고 같다</td><td>바뀌지 않았다</td></tr>
 *   <tr><td><b>한쪽에만</b> 있다</td><td>🔴 <b>주장하지 않는다</b> — 비교가 성립하지 않는다</td></tr>
 * </table>
 *
 * <p>마지막 줄이 핵심이다. 못 읽어서 지문을 못 구한 경로(5xx)와 지문 기록 이전에 만들어진
 * 정책 행이 모두 여기 해당하고, 둘 다 <b>「바뀐 것을 관측했다」가 아니다.</b>
 *
 * <h2>직렬화 — 컬럼 하나에 담는다</h2>
 *
 * <pre>AGENTS.md=absent;CONTRIBUTING.md=3b1f…;README.md=9c4a…</pre>
 *
 * <p>자식 테이블을 두지 않는 이유는 13행 고정이라 JPA 컬렉션·고아 제거 배선이 이득보다
 * 비싸기 때문이다. {@code RepositoryPolicy.pendingReason} 이 이미
 * {@code path=REASON; path=REASON} 형식을 쓰는 선례다.
 *
 * <p>🔴 <b>{@link #parse} 는 관대하지 않다.</b> 이 컬럼을 쓰는 곳은 {@link #serialize} 하나뿐이고
 * 외부 텍스트가 섞이지 않는다(S-4). 그러므로 형식이 깨졌다는 것은 <b>우리 코드의 버그</b>이고,
 * 조용히 건너뛰면 그 버그가 「변경이 탐지되지 않는다」로 위장한다.
 */
public record PolicyDocumentFingerprints(Map<String, DocumentFingerprint> byPath) {

    private static final String ENTRY_SEPARATOR = ";";
    private static final String KEY_VALUE_SEPARATOR = "=";

    public PolicyDocumentFingerprints {
        byPath = byPath == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(byPath));
    }

    public static PolicyDocumentFingerprints none() {
        return new PolicyDocumentFingerprints(Map.of());
    }

    /**
     * 수집 결과에서 지문을 뽑는다.
     *
     * <p>🔴 <b>지문이 없는 문서는 담기지 않는다.</b> 응답 자체를 못 받은 경우(5xx·레이트리밋)가
     * 그렇고, 그것을 「없음({@code absent})」으로 채우면 <b>「못 읽었다」가 「파일이 사라졌다」로
     * 번역</b>된다 — S-5 가 막는 오역 그 자체다.
     */
    public static PolicyDocumentFingerprints of(RepositoryDocuments documents) {
        Map<String, DocumentFingerprint> collected = new TreeMap<>();
        for (FetchedDocument document : documents.documents()) {
            if (document.fingerprint() != null) {
                collected.put(document.path().path(), document.fingerprint());
            }
        }
        return new PolicyDocumentFingerprints(collected);
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    public Optional<DocumentFingerprint> get(String path) {
        return Optional.ofNullable(byPath.get(path));
    }

    /**
     * 🔴 <b>판정 필수 경로 중, 양쪽에 지문이 있고 서로 다른 것</b> — 이슈 #68 FR-2.
     *
     * <p>「문서가 바뀌었는가」는 「판정이 섰는가」와 <b>독립적으로</b> 답해진다. 이 메서드가
     * 그 독립성이다 — 읽기에 실패해도 지문만 있으면 변경은 알 수 있다.
     *
     * <p>{@link PolicyDocumentPath.Role#SUPPLEMENTARY} 는 세지 않는다. README 가 바뀌었다고
     * AI 기여 허용 판정이 낡는 것은 아니고, 그것으로 강등하면 거의 매번 강등된다.
     *
     * @param current 이번에 관측한 지문
     * @return 바뀐 필수 경로. 비어 있으면 <b>「바뀐 것을 관측하지 못했다」</b>이지
     *         「바뀌지 않았음이 증명됐다」가 아니다
     */
    public List<String> changedRequiredPaths(PolicyDocumentFingerprints current) {
        List<String> changed = new ArrayList<>();
        for (PolicyDocumentPath path : PolicyDocumentPath.REQUIRED_PATHS) {
            DocumentFingerprint before = byPath.get(path.path());
            DocumentFingerprint after = current.byPath.get(path.path());
            // 🔴 한쪽이라도 없으면 비교가 성립하지 않는다 — 「바뀌었다」고 주장하지 않는다
            if (before != null && after != null && !before.equals(after)) {
                changed.add(path.path());
            }
        }
        return List.copyOf(changed);
    }

    /**
     * 🔴 <b>기준에 없던 필수 경로</b> — 이번에는 지문이 있는데 기준에는 없다.
     *
     * <p>「바뀌었다」가 아니다. 그런데 <b>「바뀌지 않았다」도 아니다.</b> 이 구분이 없으면
     * 다음이 조용히 새어 나간다.
     *
     * <ul>
     *   <li>{@link PolicyDocumentPath#REQUIRED_PATHS} 에 경로가 추가된 직후</li>
     *   <li>지난번에 응답을 못 받아 지문을 남기지 못한 경로가 이번에 읽힌 경우</li>
     * </ul>
     *
     * <p>둘 다 {@link #changedRequiredPaths} 는 빈 목록을 돌려준다(비교가 성립하지 않으므로).
     * 그것만 보고 「바뀐 것 없음」으로 LLM 을 건너뛰면 <b>그 경로의 금지 문구를 한 바퀴
     * 놓친다.</b> 그래서 호출자는 이 목록이 비어 있을 때만 판정을 건너뛴다.
     */
    public List<String> unseenRequiredPaths(PolicyDocumentFingerprints current) {
        List<String> unseen = new ArrayList<>();
        for (PolicyDocumentPath path : PolicyDocumentPath.REQUIRED_PATHS) {
            if (!byPath.containsKey(path.path()) && current.byPath.containsKey(path.path())) {
                unseen.add(path.path());
            }
        }
        return List.copyOf(unseen);
    }

    /**
     * 🔴 <b>기준에는 지문이 있었는데 이번에는 못 구한 필수 경로</b> — 이슈 #68.
     *
     * <p>{@link #changedRequiredPaths} 가 놓치는 자리다. 한쪽이 없으면 「바뀌었다」고
     * 주장하지 않는 것이 옳지만, <b>전에는 읽던 것을 이제 못 읽는 것</b>은 그 자체로 사실이다 —
     * 「우리가 판정 근거로 삼았던 문서를 지금은 확인할 수 없다」.
     *
     * <p>🔴 이것을 세지 않으면 이슈 #68 이 든 5단계 중 <b>1MB 초과 형태가 탐지되지 않는다.</b>
     * 그 경우 GitHub 이 본문을 주지 않아 지문이 없고, 「양쪽에 있을 때만」 규칙에 걸려
     * 변경으로도 잡히지 않은 채 <b>기존 허용 판정이 그대로 유지</b>된다.
     *
     * <p>⚠️ 이것만으로 강등하지 않는다. <b>영구 실패</b>와 겹칠 때만이다 — 일시적 실패
     * (5xx·레이트리밋)는 다음 스캔이 푼다.
     */
    public List<String> lostRequiredPaths(PolicyDocumentFingerprints current) {
        List<String> lost = new ArrayList<>();
        for (PolicyDocumentPath path : PolicyDocumentPath.REQUIRED_PATHS) {
            if (byPath.containsKey(path.path()) && !current.byPath.containsKey(path.path())) {
                lost.add(path.path());
            }
        }
        return List.copyOf(lost);
    }

    /** 컬럼에 넣을 형태. 경로 순으로 정렬되어 같은 관측이면 같은 문자열이 된다. */
    public String serialize() {
        return byPath.entrySet().stream()
                .map(e -> e.getKey() + KEY_VALUE_SEPARATOR + e.getValue().value())
                .reduce((a, b) -> a + ENTRY_SEPARATOR + b)
                .orElse("");
    }

    /**
     * 컬럼에서 되읽는다. {@code null}·빈 문자열은 <b>비교 기준 없음</b>이다.
     *
     * @throws IllegalArgumentException 형식이 깨졌다 — 우리 직렬화의 버그다
     */
    public static PolicyDocumentFingerprints parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return none();
        }
        Map<String, DocumentFingerprint> parsed = new TreeMap<>();
        for (String entry : serialized.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.indexOf(KEY_VALUE_SEPARATOR);
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("지문 항목 형식이 깨졌습니다: " + entry);
            }
            String path = entry.substring(0, separator);
            // DocumentFingerprint 의 생성자가 값 형식을 검증한다 — 여기서 두 벌로 두지 않는다
            parsed.put(path, new DocumentFingerprint(entry.substring(separator + 1)));
        }
        return new PolicyDocumentFingerprints(parsed);
    }

    /** 🔴 지문은 단방향 해시라 원문을 담지 않지만, 길어서 그대로 찍지 않는다. */
    @Override
    public String toString() {
        return "PolicyDocumentFingerprints[paths=%d]".formatted(byPath.size());
    }
}
