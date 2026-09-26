package com.ossagent.repository.application;

import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.ContextBudget;
import com.ossagent.repository.domain.ContextKeyword;
import com.ossagent.repository.domain.ContextKeywords;
import com.ossagent.repository.domain.ExcludedPathReason;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RelevanceScorer;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryPathPolicy;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.RepositoryTree;
import com.ossagent.repository.domain.RepositoryTreeEntry;
import com.ossagent.repository.domain.SelectedFile;
import com.ossagent.repository.domain.SelectionReason;
import com.ossagent.support.github.GitHubUnreadableContentException;
import com.ossagent.support.secret.SecretFilePolicy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 이슈 하나에 대해 <b>대상 저장소에서 볼 파일을 좁힌다</b> — 이슈 #15 · PRD §12.
 *
 * <pre>
 *   assertContributionAllowed(repositoryId)  ← 🔴 S-5. 보류·금지면 여기서 끝난다
 *   ↓
 *   메타데이터 1회   기본 브랜치를 알아야 트리를 읽을 ref 가 생긴다
 *   트리 1회         저장소 전체 경로
 *   ↓  키워드 추출(순수) → 점수(순수) → 예산 안에서 선별
 *   파일 N회         🔴 SecretFilePolicy 를 통과한 경로만
 *   ↓
 *   RepositoryContext  → #16 이 프롬프트로 만든다
 * </pre>
 *
 * <h2>🔴 트랜잭션 안에서 부르지 않는다</h2>
 * 대외 호출이 전부다. {@code @Transactional} 을 붙이지 않았고, 호출자가 감싸는 것을
 * {@link #assertNoTransaction()} 이 막는다 — {@code AnalyzeIssuesUseCase} 와 같은 장치다.
 * 이 클래스 자체는 DB 에 쓰지 않는다(PLAN-15 D-2 — 영속화 없음).
 *
 * <h2>실패를 어떻게 가르는가</h2>
 * <table>
 *   <tr><th>무엇</th><th>어떻게</th></tr>
 *   <tr><td>규약 보류·금지 (S-5)</td><td>🔴 <b>전체 중단</b> — {@code ContributionNotAllowedException}</td></tr>
 *   <tr><td>레이트리밋</td><td><b>전파</b>한다. 실패가 아니라 <b>지연</b>이고, 번역은 호출자 몫이다</td></tr>
 *   <tr><td>트리 조회 실패</td><td>전파 — 아무것도 못 골랐다는 뜻이다</td></tr>
 *   <tr><td><b>파일 하나</b>를 못 읽음</td><td>🔴 <b>건너뛰고 집계</b>한다. 파일 하나가 단계 전체를 실패시키지 않는다</td></tr>
 * </table>
 *
 * <p>마지막 줄이 규약 판정(S-5)과 방향이 다른 이유 — 가르는 것은 보수성의 정도가 아니라
 * <b>실패의 방향이 되돌릴 수 있는가</b>다({@code external-deps.md}). 규약을 못 읽고 통과시키면
 * 남의 저장소에 위반 PR 이 나가지만, 관련 파일 하나를 못 보면 계획 품질이 떨어질 뿐이다.
 */
@Service
public class BuildRepositoryContextUseCase {

    private static final Logger log = LoggerFactory.getLogger(BuildRepositoryContextUseCase.class);

    /** {@code import org.springframework.kafka.listener.KafkaMessageListenerContainer;} */
    private static final Pattern IMPORT_STATEMENT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([a-zA-Z_][\\w.]*)\\s*;");

    /** import 확장으로 볼 파일 수의 상한 — 예산과 별개로 폭주를 막는다 */
    private static final int MAX_IMPORT_NEIGHBORS = 8;

    private final OssRepositoryRepository repositories;
    private final AnalyzeRepositoryPolicyUseCase policyGate;
    private final RepositorySource repositorySource;
    private final RepositoryContextProperties properties;

    public BuildRepositoryContextUseCase(OssRepositoryRepository repositories,
            AnalyzeRepositoryPolicyUseCase policyGate,
            RepositorySource repositorySource,
            RepositoryContextProperties properties) {
        this.repositories = repositories;
        this.policyGate = policyGate;
        this.repositorySource = repositorySource;
        this.properties = properties;
    }

    /**
     * 이슈와 관련 있는 파일을 골라 {@link RepositoryContext} 로 만든다.
     *
     * <p>🔴 <b>결과가 비어 있어도 예외가 아니다.</b> 이슈에 좁힐 신호가 없었다는 사실이고,
     * 그것을 {@code files.isEmpty()} 로 호출자가 본다 — 억지로 채우면 #16 이 엉뚱한 파일로
     * 계획을 세운다.
     *
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         🔴 규약을 읽지 못했거나(보류) AI 기여가 금지된 저장소 — S-5
     * @throws com.ossagent.support.github.GitHubRateLimitException
     *         레이트리밋. <b>실패가 아니라 지연</b>이다 — 호출자가 그렇게 번역한다
     */
    public RepositoryContext build(AnalyzableIssue issue) {
        if (issue == null) {
            throw new IllegalArgumentException("분석할 이슈는 필수다");
        }
        assertNoTransaction();

        // 🔴 S-5 — 대상 저장소 소스를 끌어오기 전에 판정한다. 보류(UNDETERMINED)도 금지와 같이 막는다.
        //    AnalyzeIssuesUseCase(#11) 가 같은 자리에서 같은 게이트를 세운다. 이 단계는 그보다
        //    더 나아가 저장소의 실제 코드를 LLM 입력으로 만들기 시작하므로 면제될 이유가 없다
        policyGate.assertContributionAllowed(issue.repositoryId());

        RepositoryCoordinates coordinates = coordinatesOf(issue.repositoryId());
        String ref = repositorySource.fetchMetadata(coordinates).defaultBranch();
        if (ref == null || ref.isBlank()) {
            throw new GitHubUnreadableContentException(
                    "기본 브랜치를 알 수 없어 저장소를 분석하지 못했습니다 repo="
                            + coordinates.fullName());
        }

        RepositoryTree tree = repositorySource.fetchTree(coordinates, ref);
        List<ContextKeyword> keywords = ContextKeywords.from(
                new ContextKeywords.AnalyzableIssueText(issue.title(), issue.body()),
                properties.maxKeywords());
        List<RelevanceScorer.ScoredPath> ranked = RelevanceScorer.rank(tree, keywords);

        Selection selection = new Selection(coordinates, ref, tree, issue.githubIssueNumber());
        selection.takeRanked(ranked);
        selection.takeTestPairs();
        if (properties.importExpansionEnabled()) {
            selection.takeImportNeighbors();
        }
        return selection.toContext(tree, ranked.size());
    }

    private RepositoryCoordinates coordinatesOf(Long repositoryId) {
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        return new RepositoryCoordinates(repository.getOwner(), repository.getName());
    }

    /**
     * 선별 진행 상태. 예산·배제 집계가 여러 단계를 넘어가므로 값 타입으로 두지 않았다.
     *
     * <p>{@code Selection} 이 바깥 클래스의 필드를 쓰므로 비정적 내부 클래스다.
     */
    private final class Selection {

        private final RepositoryCoordinates coordinates;
        private final String ref;
        private final Integer issueNumber;
        private final Map<String, RepositoryTreeEntry> entriesByPath;
        /** 파일 이름 → 그 이름을 가진 경로들. import 이웃 탐색이 전체를 훑지 않게 한다 */
        private final Map<String, List<String>> pathsByFileName = new LinkedHashMap<>();
        private final Map<String, SelectedFile> selected = new LinkedHashMap<>();
        private final Map<ExcludedPathReason, Integer> excluded =
                new EnumMap<>(ExcludedPathReason.class);
        private ContextBudget budget;
        /**
         * 🔴 읽기를 <b>시도한</b> 횟수. 파일 예산과 다른 축이다 — 실패한 읽기는 예산을
         * 쓰지 않으므로, 이 카운터가 없으면 실패가 계속될 때 후보 전량을 두드린다.
         */
        private int fetchAttempts;

        private Selection(RepositoryCoordinates coordinates, String ref, RepositoryTree tree,
                Integer issueNumber) {
            this.coordinates = coordinates;
            this.ref = ref;
            this.issueNumber = issueNumber;
            this.budget = ContextBudget.of(properties.maxFiles(), properties.maxTotalChars(),
                    properties.maxFileChars());
            this.entriesByPath = new LinkedHashMap<>();
            for (RepositoryTreeEntry entry : tree.blobs()) {
                entriesByPath.put(entry.path(), entry);
                pathsByFileName.computeIfAbsent(entry.fileName(), key -> new ArrayList<>())
                        .add(entry.path());
            }
        }

        private void takeRanked(List<RelevanceScorer.ScoredPath> ranked) {
            for (RelevanceScorer.ScoredPath candidate : ranked) {
                tryTake(candidate.path(), candidate.score(), candidate.reason());
            }
        }

        private void takeTestPairs() {
            for (String path : List.copyOf(selected.keySet())) {
                for (String testPath : RelevanceScorer.testPairsOf(entriesByPath.keySet(), path)) {
                    if (selected.containsKey(testPath)) {
                        promoteToTestPair(testPath);
                        continue;
                    }
                    tryTake(testPath, RelevanceScorer.testPairScore(), SelectionReason.TEST_PAIR);
                }
            }
        }

        /**
         * 이미 들어온 파일이 <b>더 약한 사유</b>로 뽑혔다면 짝 관계로 고쳐 단다.
         *
         * <p>🔴 {@code FooTests.java} 는 이름에 {@code Foo} 가 들어 있어 낱말 매칭으로도
         * 걸린다. 그대로 두면 사유가 {@code TERM} 으로 남는데, 그것은 <b>덜 정확한 설명</b>이다 —
         * 사람이 「왜 이 파일이 있지」를 물을 때 필요한 답은 「{@code Foo} 의 테스트라서」이지
         * 「경로에 foo 라는 글자가 있어서」가 아니다. 사유는 FR-5 의 산출물이므로
         * 더 나은 설명이 있으면 그쪽으로 바꾼다.
         */
        private void promoteToTestPair(String testPath) {
            SelectedFile existing = selected.get(testPath);
            if (existing.score() >= RelevanceScorer.testPairScore()) {
                return;
            }
            selected.put(testPath, new SelectedFile(testPath, SelectionReason.TEST_PAIR,
                    RelevanceScorer.testPairScore(), existing.content()));
        }

        /**
         * 고른 소스가 import 하는 <b>같은 저장소 안의</b> 파일을 남은 예산만큼 채운다 (FR-7).
         *
         * <p>1-hop 뿐이다. 깊이를 늘리면 예산이 먼 파일로 새고, 그 파일들은 이슈와
         * 직접 관련이 없다.
         */
        private void takeImportNeighbors() {
            Set<String> neighbors = new LinkedHashSet<>();
            for (SelectedFile file : List.copyOf(selected.values())) {
                if (file.reason() == SelectionReason.IMPORT_NEIGHBOR) {
                    continue;
                }
                neighbors.addAll(importedPathsOf(file));
                if (neighbors.size() >= MAX_IMPORT_NEIGHBORS) {
                    break;
                }
            }
            for (String path : neighbors) {
                if (!budget.hasRoom()) {
                    return;
                }
                tryTake(path, RelevanceScorer.importNeighborScore(),
                        SelectionReason.IMPORT_NEIGHBOR);
            }
        }

        /**
         * {@code import a.b.C;} → 트리에 실재하는 {@code .../a/b/C.java} 경로.
         *
         * <p>⚠️ 경로 전체를 훑지 않는다. 대상 저장소는 파일이 수만 개일 수 있고, 여기는
         * (고른 파일 × import 문) 만큼 반복되는 자리다. <b>파일 이름으로 먼저 좁힌 뒤</b>
         * 접미사를 맞춘다 — 같은 이름의 클래스는 저장소 안에서 몇 개 되지 않는다.
         */
        private Set<String> importedPathsOf(SelectedFile file) {
            Set<String> found = new LinkedHashSet<>();
            Matcher matcher = IMPORT_STATEMENT.matcher(file.content());
            while (matcher.find() && found.size() < MAX_IMPORT_NEIGHBORS) {
                String qualified = matcher.group(1);
                int lastDot = qualified.lastIndexOf('.');
                if (lastDot < 0) {
                    continue;
                }
                String fileName = qualified.substring(lastDot + 1) + ".java";
                String suffix = "/" + qualified.replace('.', '/') + ".java";
                for (String path : pathsByFileName.getOrDefault(fileName, List.of())) {
                    if (path.endsWith(suffix)) {
                        found.add(path);
                        break;
                    }
                }
            }
            return found;
        }

        /** 한 경로를 담아 본다. 배제·예산·읽기 실패를 전부 여기서 집계한다 */
        private void tryTake(String path, int score, SelectionReason reason) {
            if (path == null || selected.containsKey(path)) {
                return;
            }
            // 🔴 S-4 ① — 경로를 보고 「애초에 열지 않는다」. 아래 fetchFile 보다 반드시 앞이다.
            //    이것을 빼면 .env · id_rsa 가 그대로 읽혀 프롬프트로 간다
            if (SecretFilePolicy.isSecretPath(path)) {
                count(ExcludedPathReason.SECRET_PATH);
                return;
            }
            RepositoryTreeEntry entry = entriesByPath.get(path);
            if (entry == null || !RepositoryPathPolicy.isSelectableSource(entry)) {
                count(ExcludedPathReason.NOT_SOURCE);
                return;
            }
            // 트리가 알려준 크기로 먼저 거른다 — 읽고 나서 버리면 호출을 낭비한다
            if (entry.size() > properties.maxFileChars()) {
                count(ExcludedPathReason.TOO_LARGE);
                budget = budget.markTruncated();
                return;
            }
            if (!budget.hasRoom() || fetchAttempts >= properties.maxFetchAttempts()) {
                // 🔴 두 상한을 함께 본다. 파일 예산만 보면 실패가 계속될 때 멈추지 않는다 —
                //    흔한 낱말 하나가 수천 경로에 걸릴 수 있고(TERM 은 경로 부분 문자열
                //    매칭이다), 그 호출이 전부 실패하면 저장소 하나가 시간당 예산을 태워
                //    같은 토큰을 쓰는 #7·#8 까지 막는다
                count(ExcludedPathReason.BUDGET_EXHAUSTED);
                budget = budget.markTruncated();
                return;
            }

            fetchAttempts++;
            Optional<RepositoryFile> fetched = fetchOrSkip(path);
            if (fetched.isEmpty()) {
                return;
            }
            // 🔴 스크럽은 SelectedFile 생성자가 강제한다 — S-4 ②. 여기서 부르지 않는다.
            //    부를 수 있게 두면 언젠가 빠뜨린다
            SelectedFile file = new SelectedFile(path, reason, score, fetched.get().content());
            if (!budget.fits(file.size())) {
                // 트리의 size 는 바이트, 여기는 문자다. 어긋날 수 있어 읽은 뒤 한 번 더 본다.
                // ⚠ 사유를 뭉뚱그리지 않는다 — 「이 파일이 컸다」와 「예산이 떨어졌다」는
                //    다음에 고칠 것이 다르다(파일당 상한 vs 전체 상한). FR-5 의 근거 기록이다
                count(file.size() > properties.maxFileChars()
                        ? ExcludedPathReason.TOO_LARGE
                        : ExcludedPathReason.BUDGET_EXHAUSTED);
                budget = budget.markTruncated();
                return;
            }
            selected.put(path, file);
            budget = budget.consume(file.size());
        }

        /**
         * 🔴 <b>파일 하나의 실패가 단계 전체를 실패시키지 않는다.</b>
         *
         * <p>잡는 것은 「이 파일을 못 읽었다」뿐이다. 레이트리밋·권한은
         * {@code GitHubUnreadableContentException} 이 아니므로 <b>그대로 전파</b>된다 —
         * 그것은 파일의 문제가 아니라 우리 호출 전체의 문제다.
         */
        private Optional<RepositoryFile> fetchOrSkip(String path) {
            try {
                Optional<RepositoryFile> fetched =
                        repositorySource.fetchFile(coordinates, path, ref);
                if (fetched.isEmpty()) {
                    // 트리에는 있었는데 없다 — ref 가 움직였거나 그 사이 지워졌다 (PLAN-15 R-2)
                    count(ExcludedPathReason.NOT_FOUND);
                }
                return fetched;
            } catch (GitHubUnreadableContentException e) {
                // 1MB 초과 · 디렉터리 · 심볼릭링크 · 디코드 실패
                count(ExcludedPathReason.UNREADABLE);
                // ⚠ 예외 메시지를 찍지 않는다 — 대상 저장소 경로·내용이 섞여 있을 수 있다
                log.debug("대상 저장소 파일을 읽지 못해 건너뛴다 repo={} path={}",
                        coordinates.fullName(), path);
                return Optional.empty();
            }
        }

        private void count(ExcludedPathReason reason) {
            excluded.merge(reason, 1, Integer::sum);
        }

        private RepositoryContext toContext(RepositoryTree source, int scannedPaths) {
            RepositoryContext context = new RepositoryContext(coordinates, ref, source.sha(),
                    List.copyOf(selected.values()), budget, source.truncated(), scannedPaths,
                    excluded);
            // 🔴 경로도 내용도 찍지 않는다 — 집계만. logging.md 「대용량 payload 는 크기와 해시만」
            log.info("컨텍스트 선별 repo={} issue=#{} 후보={} 선택={} 문자={} 절단={} 배제={}",
                    coordinates.fullName(), issueNumber, scannedPaths,
                    context.files().size(), context.totalChars(), context.isPartial(), excluded);
            return context;
        }
    }

    /**
     * 🔴 대외 호출이 트랜잭션 안에 들어가는 것을 막는다.
     *
     * <p>이 클래스는 {@code @Transactional} 을 붙이지 않았지만, 호출자(#16)가 감싸면
     * <b>규율이 조용히 깨진다.</b> 증상이 「느리다」뿐이라 리뷰에서도 놓치기 쉽다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "저장소 분석을 트랜잭션 안에서 부를 수 없다 — GitHub 호출이 커넥션을 점유한다. "
                            + "호출자의 @Transactional 을 제거한다 (architecture.md 규율)");
        }
    }
}
