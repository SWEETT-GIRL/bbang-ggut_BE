package sweetgirl.croissant.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.jwt.JwtProvider;

/**
 * 부하 측정용 토큰(scripts/perf/generate-tokens.mjs 가 Node 로 서명한 것)을
 * 이 앱의 {@link JwtProvider} 가 실제로 받아들이는지 본다.
 *
 * <p>서명이 어긋나면 5만 건이 전부 401 이 되어 회차가 통째로 날아간다. 그 사실을 k6 결과에서야 알면
 * 시드·토큰 생성·측정을 다시 돌려야 하므로, 측정 전에 여기서 걸리게 둔다.
 *
 * <p>Node 쪽은 알고리즘과 클레임을 손으로 맞춘 구현이다 — jjwt 의 {@code Keys.hmacShaKeyFor} 가
 * secret 길이에 따라 HS256/384/512 중 하나를 고르므로, secret 이 바뀌면 이 테스트가 먼저 깨진다.
 *
 * <p>tokens.csv 가 없으면 건너뛴다. 측정 준비를 한 적 없는 환경(CI·새 클론)에서 빌드를 깨지 않기 위해서다.
 */
class LoadTestTokenCompatibilityTest {

	private static final Path TOKENS = Path.of("scripts/perf/tokens.csv");
	private static final Path LOCAL_PROPERTIES = Path.of("src/main/resources/application-local.properties");

	private JwtProvider jwtProviderFromLocalProperties() throws IOException {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(LOCAL_PROPERTIES, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return new JwtProvider(
			properties.getProperty("jwt.secret"),
			Long.parseLong(properties.getProperty("jwt.access-token-expiry")),
			Long.parseLong(properties.getProperty("jwt.refresh-token-expiry")));
	}

	/** 액세스 토큰은 1시간짜리다. 만료된 파일로는 서명이 맞는지 가릴 수 없으므로 건너뛴다. */
	private boolean notExpired(String token) {
		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
		long expiresAt = Long.parseLong(payload.replaceAll(".*\"exp\":(\\d+).*", "$1"));
		return Instant.ofEpochSecond(expiresAt).isAfter(Instant.now());
	}

	@Test
	@DisplayName("Node 로 서명한 측정용 토큰을 JwtProvider 가 검증하고 memberId 를 그대로 꺼낸다")
	void generatedTokens_areAcceptedByJwtProvider() throws IOException {
		assumeTrue(Files.exists(TOKENS), "측정 준비를 한 적이 없으면 건너뛴다 (generate-tokens.mjs 미실행)");

		JwtProvider jwtProvider = jwtProviderFromLocalProperties();

		List<String> sample;
		try (var lines = Files.lines(TOKENS, StandardCharsets.UTF_8)) {
			sample = lines.limit(5).toList();
		}
		assertThat(sample).as("tokens.csv 가 비어 있다").isNotEmpty();
		assumeTrue(notExpired(sample.get(0).split(",", 2)[1]),
			"토큰이 만료됐다. 측정 직전에 generate-tokens.mjs 로 새로 뽑아야 한다");

		for (String line : sample) {
			String[] columns = line.split(",", 2);
			assertThat(columns).as("`memberId,token` 두 칸이어야 한다: %s", line).hasSize(2);
			Long expectedMemberId = Long.valueOf(columns[0]);
			String token = columns[1];

			assertThat(jwtProvider.validateToken(token))
				.as("memberId %d 의 토큰이 서명 검증에 실패했다 — secret 이나 알고리즘이 어긋났다", expectedMemberId)
				.isTrue();
			assertThat(jwtProvider.getMemberId(token)).isEqualTo(expectedMemberId);
		}
	}
}
