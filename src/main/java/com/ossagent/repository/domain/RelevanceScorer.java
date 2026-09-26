package com.ossagent.repository.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 트리 경로 × 키워드 → 점수와 사유. <b>순수 함수다</b> — 이슈 #15 FR-2.
 *
 * <p>내용을 보지 않고 <b>경로만</b> 본다. 그것이 Trees API 를 택한 대가이자 이득이다
 * (PLAN-15 D-1) — 호출 1회로 저장소 전체를 훑을 수 있지만, 이슈가 클래스 이름도 경로도
 * 적지 않았다면 건질 것이 없다. 그 경우 결과가 <b>비어 있는 것이 정직한 답</b>이고,
 * 억지로 채우면 #16 이 엉뚱한 파일로 계획을 세운다.
 */
public final class RelevanceScorer {

    /** 경로 전체가 걸렸을 때의 배수 — 파일 이름만 걸린 것보다 강하다 */
    private static final int FULL_PATH_MULTIPLIER = 2;

    /** 타입 이름이 파일 이름과 <b>정확히</b> 같을 때의 배수 */
    private static final int EXACT_TYPE_MULTIPLIER = 2;

    /** 테스트 짝의 점수 — 원본보다 낮되 낱말보다는 높다 */
    private static final int TEST_PAIR_SCORE = 30;

    /** import 이웃의 점수 — 가장 낮다. 남은 예산을 채우는 용도다 */
    private static final int IMPORT_NEIGHBOR_SCORE = 10;

    /** 테스트 파일 접미사 — Spring 계열은 {@code Tests} 를 더 많이 쓴다 */
    private static final List<String> TEST_SUFFIXES = List.of("Tests", "Test", "IT", "ITCase");

    private RelevanceScorer() {
    }

    /** 경로 하나의 판정 결과 */
    public record ScoredPath(String path, int score, SelectionReason reason) {
    }

    /**
     * 후보를 점수 순으로 매긴다. <b>0점은 후보가 아니다</b> — 목록에서 빠진다.
     *
     * <p>🔴 정렬이 결정적이다. 점수가 같으면 <b>경로 사전순</b>으로 깬다 —
     * 같은 입력에 같은 선별이 나와야 재현이 가능하다(NFR-4).
     */
    public static List<ScoredPath> rank(RepositoryTree tree, List<ContextKeyword> keywords) {
        if (tree == null || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<ScoredPath> scored = new ArrayList<>();
        for (RepositoryTreeEntry entry : tree.entries()) {
            if (!RepositoryPathPolicy.isSelectableSource(entry)) {
                continue;
            }
            ScoredPath result = score(entry, keywords);
            if (result.score() > 0) {
                scored.add(result);
            }
        }
        scored.sort(Comparator.comparingInt(ScoredPath::score).reversed()
                .thenComparing(ScoredPath::path));
        return List.copyOf(scored);
    }

    private static ScoredPath score(RepositoryTreeEntry entry, List<ContextKeyword> keywords) {
        String path = entry.path().toLowerCase(Locale.ROOT);
        String baseName = entry.baseName().toLowerCase(Locale.ROOT);
        String fileName = entry.fileName().toLowerCase(Locale.ROOT);

        int total = 0;
        // 사유는 「가장 크게 기여한 하나」다. 여러 신호가 걸려도 사람이 읽을 때 필요한 것은
        // 「무엇 때문에 올라왔나」 하나이고, 전부 나열하면 근거가 아니라 소음이 된다
        int bestContribution = 0;
        SelectionReason bestReason = null;

        for (ContextKeyword keyword : keywords) {
            int contribution = contributionOf(keyword, path, fileName, baseName);
            if (contribution <= 0) {
                continue;
            }
            total += contribution;
            if (contribution > bestContribution) {
                bestContribution = contribution;
                bestReason = reasonOf(keyword.kind());
            }
        }
        return new ScoredPath(entry.path(), total, bestReason);
    }

    private static int contributionOf(ContextKeyword keyword, String path, String fileName,
            String baseName) {
        String value = keyword.normalized();
        int weight = keyword.weight();

        return switch (keyword.kind()) {
            case PATH_LITERAL -> {
                // 「src/main/java/org/x/Foo.java」처럼 경로째 걸리면 가장 강하다.
                // 「Foo.java」만 적혀 있으면 파일 이름으로 건진다
                if (value.contains("/") && path.endsWith(value)) {
                    yield weight * FULL_PATH_MULTIPLIER;
                }
                yield fileName.equals(value) || fileName.equals(lastSegment(value)) ? weight : 0;
            }
            case TYPE_NAME -> {
                if (baseName.equals(value)) {
                    yield weight * EXACT_TYPE_MULTIPLIER;
                }
                // KafkaTemplate 이슈에서 KafkaTemplateTests 가 걸리게 한다.
                // 반대 방향(짧은 파일명이 긴 키워드에 포함)은 세지 않는다 — Foo 가
                // FooBarBazContainer 키워드에 걸려 무관한 파일이 올라온다
                yield baseName.contains(value) ? weight : 0;
            }
            // 패키지는 디렉터리다. org.x.y → org/x/y
            case PACKAGE -> path.contains(value.replace('.', '/')) ? weight : 0;
            case TERM -> path.contains(value) ? weight : 0;
        };
    }

    private static SelectionReason reasonOf(ContextKeyword.Kind kind) {
        return switch (kind) {
            case PATH_LITERAL -> SelectionReason.PATH_LITERAL;
            case TYPE_NAME -> SelectionReason.TYPE_NAME;
            case PACKAGE -> SelectionReason.PACKAGE;
            case TERM -> SelectionReason.TERM;
        };
    }

    /**
     * 고른 소스의 <b>테스트 짝</b> 경로를 찾는다 — FR-3.
     *
     * <p>이슈 본문의 흐름이 「코드 검색 · 테스트 검색」을 나란히 둔 이유는, 기여 규약이
     * 테스트를 요구하는 저장소가 많아서다(S-5 · {@code RepositoryPolicy.tests_required}).
     * 고칠 파일만 보여 주면 모델이 <b>그 저장소의 테스트 관습</b>을 모른 채 테스트를 쓴다.
     *
     * @return 트리에 실재하는 테스트 경로만. 없으면 빈 목록
     */
    public static List<String> testPairsOf(RepositoryTree tree, String sourcePath) {
        if (tree == null || sourcePath == null || sourcePath.isBlank()) {
            return List.of();
        }
        int dot = sourcePath.lastIndexOf('.');
        if (dot <= 0) {
            return List.of();
        }
        String withoutExtension = sourcePath.substring(0, dot);
        String extension = sourcePath.substring(dot);

        List<String> found = new ArrayList<>();
        for (String suffix : TEST_SUFFIXES) {
            String sameDirectory = withoutExtension + suffix + extension;
            if (tree.containsBlob(sameDirectory)) {
                found.add(sameDirectory);
            }
            // 표준 메이븐/그래들 배치 — src/main/java/... ↔ src/test/java/...
            String testTree = sameDirectory.replace("/main/", "/test/");
            if (!testTree.equals(sameDirectory) && tree.containsBlob(testTree)) {
                found.add(testTree);
            }
        }
        return List.copyOf(found);
    }

    public static int testPairScore() {
        return TEST_PAIR_SCORE;
    }

    public static int importNeighborScore() {
        return IMPORT_NEIGHBOR_SCORE;
    }

    private static String lastSegment(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
