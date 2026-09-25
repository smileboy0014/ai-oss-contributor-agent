package com.ossagent.repository.adapter.out.github;

import com.ossagent.repository.domain.FetchedDocument;
import com.ossagent.repository.domain.PolicyDocumentPath;
import com.ossagent.repository.domain.PolicyDocumentSource;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.UnreadableReason;
import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.github.GitHubRateLimitException;
import com.ossagent.support.github.GitHubTransientException;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 규약 후보 문서를 모으고 <b>실패를 우리 어휘로 번역</b>한다 — S-5.
 *
 * <p>{@code RepositorySource} 는 읽기 실패를 예외로 전파한다(#6 계약). 여기서 그것을 값으로
 * 바꾼다 — 한 경로가 5xx 라고 나머지 수집을 포기할 수 없고, <b>무엇이 실패했는지가 곧 보류 사유</b>다.
 *
 * <p>번역을 어댑터에 두는 이유는 UseCase 가 GitHub 예외 타입에 묶이지 않게 하기 위해서다 —
 * #10 이 SDK 예외를 {@code LlmFailureReason} 으로 옮긴 것과 같다.
 *
 * <p>🔴 {@code @ExternalAdapter} — 실제 네트워크를 탄다. {@code fakes} 프로파일에서 빠지고
 * {@code FakePolicyDocumentSource} 가 대신 뜬다.
 */
@Component
@ExternalAdapter
public class GitHubPolicyDocumentSource implements PolicyDocumentSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubPolicyDocumentSource.class);

    private final RepositorySource repositorySource;
    private final int maxDocumentChars;

    public GitHubPolicyDocumentSource(RepositorySource repositorySource, int maxDocumentChars) {
        this.repositorySource = repositorySource;
        this.maxDocumentChars = maxDocumentChars;
    }

    @Override
    public RepositoryDocuments collect(RepositoryCoordinates coordinates,
            List<PolicyDocumentPath> paths) {
        List<FetchedDocument> results = new ArrayList<>(paths.size());
        for (PolicyDocumentPath path : paths) {
            results.add(fetchOne(coordinates, path));
        }
        log.info("규약 문서 수집 repo={} 경로={} 읽음={}",
                coordinates.fullName(), paths.size(),
                results.stream().filter(FetchedDocument::isRead).count());
        return new RepositoryDocuments(results);
    }

    /**
     * 🔴 <b>fail-closed.</b> 수집 호출 <b>그 한 줄만</b> 감싼다.
     *
     * <p>넓게 감싸면 우리 쪽 버그(NPE·IllegalArgument)가 「저장소를 못 읽었다」로 둔갑해
     * <b>보류 뒤에 숨는다.</b> 그래서 판정·조립 로직은 이 try 밖에 둔다.
     *
     * <p>타입을 쫓아가지 않고 {@code RuntimeException} 전부를 받는 이유 — S-5 에서 가르는 선은
     * 「무슨 오류인가」가 아니라 <b>「읽었는가」</b>다. 실제로 읽기 타임아웃이
     * {@code CancellationException} 으로 타입 없이 올라오는 구멍이 있다(#12 세션 확정).
     */
    private FetchedDocument fetchOne(RepositoryCoordinates coordinates, PolicyDocumentPath path) {
        Optional<RepositoryFile> file;
        try {
            file = repositorySource.fetchFile(coordinates, path.path(), null);
        } catch (RuntimeException e) {
            UnreadableReason reason = translate(e);
            // ⚠ 예외 원문을 남기지 않는다 — 요청 URL·헤더에 토큰이 붙어 있을 수 있다 (S-4)
            log.warn("규약 문서를 읽지 못했다 repo={} path={} reason={}",
                    coordinates.fullName(), path.path(), reason);
            return FetchedDocument.unreadable(path, reason);
        }

        if (file.isEmpty()) {
            // 🔴 Optional.empty() 는 404 하나뿐이다 — #6 계약. 다른 실패를 여기로 넣으면
            //    「규약 없음 → 허용」 오역이 된다
            return FetchedDocument.absent(path);
        }

        String content = file.get().content();
        if (content.length() > maxDocumentChars) {
            // 자르지 않는다. 잘린 뒷부분에 금지 문구가 있었는지 판정할 방법이 없다
            log.warn("규약 문서가 상한을 넘었다 repo={} path={} size={} cap={}",
                    coordinates.fullName(), path.path(), content.length(), maxDocumentChars);
            return FetchedDocument.unreadable(path, UnreadableReason.TRUNCATED);
        }
        return FetchedDocument.read(path, content);
    }

    /**
     * GitHub 예외를 우리 어휘로.
     *
     * <p>{@code GitHubUnreadableContentException} 을 {@code TOO_LARGE} 로 단정하지 않는다 —
     * 1MB 초과와 base64 디코드 실패·디렉터리 경로가 <b>같은 타입</b>으로 오므로,
     * 임의로 좁히면 거짓 진단이 된다. 구분이 필요해지면 그 예외가 사유를 담게 하는 것이 먼저다.
     */
    private static UnreadableReason translate(RuntimeException e) {
        if (e instanceof GitHubRateLimitException) {
            return UnreadableReason.RATE_LIMITED;
        }
        if (e instanceof GitHubTransientException) {
            return UnreadableReason.SERVER_ERROR;
        }
        if (e instanceof GitHubUnreadableContentException) {
            return UnreadableReason.UNKNOWN;
        }
        // 권한·인증·분류되지 않는 오류. 재시도해도 같으므로 영구 실패로 본다
        return UnreadableReason.UNKNOWN;
    }
}
