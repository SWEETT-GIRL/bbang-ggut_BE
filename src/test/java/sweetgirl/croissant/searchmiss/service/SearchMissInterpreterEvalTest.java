package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.llm.LlmTestAdapters;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmModelRef;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.dto.NaverBlogPost;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.service.SearchMissKeywordInterpreter.InterpretAnswer;

/**
 * 실패 검색어 해석의 <b>평가 루프</b>. 진짜 LLM 을 불러 고정된 평가셋(실제 검색어 + 실제 블로그 글)을 채점한다.
 *
 * <pre>
 * CLAUDE_API_KEY=… GEMINI_API_KEY=… OPENAI_API_KEY=… \
 *   ./gradlew test -PllmTests --tests "*SearchMissInterpreterEvalTest" -i
 * </pre>
 *
 * <p>환경 변수로 조절한다.
 * <ul>
 *   <li>{@code EVAL_MODELS} — {@code 벤더:모델[@출력토큰상한]} 쉼표 목록. 비우면 {@link #DEFAULT_MODELS}</li>
 *   <li>{@code EVAL_RUNS} — 케이스마다 몇 번 돌릴지(기본 3). LLM 은 같은 입력에도 답이 흔들려 한 번으로는 판단하지 않는다</li>
 *   <li>{@code EVAL_CONCURRENCY} — 동시 호출 수(기본 4)</li>
 * </ul>
 *
 * <p>채점은 운영 코드 기준이다. 모델 답을 {@link SearchMissKeywordInterpreter#verify}에 그대로 통과시켜
 * <b>치명</b>(가게가 아닌데 STORE로 통과, 다른 가게 이름으로 통과)과 <b>놓침</b>(가게인데 통과 못 해 사람 검토로 감)을 가른다.
 * 운영 모델이 치명 0 · refersTo 정확도 {@value #MIN_ACCURACY} 이상을 못 넘기면 테스트가 실패한다(CI 게이트).
 */
@Tag("llm")
class SearchMissInterpreterEvalTest {

	private static final String CASES_FILE = "/searchmiss/eval-cases.json";
	private static final double MIN_ACCURACY = 0.80;
	private static final int DEFAULT_RUNS = 3;
	private static final int DEFAULT_CONCURRENCY = 4;
	private static final Path REPORT_DIR = Path.of("build", "reports", "search-miss-eval");
	private static final int MAX_ATTEMPTS = 4;
	private static final long RETRY_BACKOFF_MS = 2_000;
	private static final int ERROR_BODY_LIMIT = 300;
	private static final Set<LlmErrorCode> RETRYABLE = Set.of(LlmErrorCode.RATE_LIMITED, LlmErrorCode.UNAVAILABLE);

	/**
	 * 비교 후보. 사고(thinking)하는 모델은 운영 상한(300)에서 잘리므로 상한을 따로 준다.
	 * 첫 줄이 지금 운영 모델이다.
	 */
	private static final List<String> DEFAULT_MODELS = List.of(
		"ANTHROPIC:claude-haiku-4-5-20251001",
		"ANTHROPIC:claude-haiku-5-5@1024",
		"ANTHROPIC:claude-sonnet-5-5@2048",
		"GEMINI:gemini-3.5-flash-lite",
		"OPENAI:gpt-5.6-luna@1024");

	/**
	 * 1M 토큰당 달러(입력, 출력). 2026-11-20 기준 — Anthropic 은 공식 가격표, Gemini · OpenAI 는 가격 집계 사이트 값이라
	 * 바뀌면 여기만 고친다. 없는 모델은 비용 칸이 비어 나온다.
	 */
	private static final Map<String, double[]> PRICE_PER_MILLION = Map.of(
		"claude-haiku-4-5-20251001", new double[] {1.00, 5.00},
		"claude-haiku-5-5", new double[] {0.10, 0.50},
		"claude-sonnet-5-5", new double[] {2.00, 10.00},
		"gemini-3.5-flash-lite", new double[] {0.30, 2.50},
		"gpt-5.6-luna", new double[] {0.20, 1.20});

	@Test
	@DisplayName("평가셋을 모델마다 돌려 정확도·치명·놓침·지연·비용을 표로 남기고, 운영 모델이 기준을 넘는지 본다")
	void evaluate() throws Exception {
		List<LlmClient> clients = LlmTestAdapters.fromEnvironment();
		assumeThat(clients).as("LLM 키가 하나도 없다 — 환경 변수로 키를 주고 돌린다").isNotEmpty();
		LlmClientRegistry registry = new LlmClientRegistry(clients);

		SearchMissTriageProperties properties = new SearchMissTriageProperties();
		String gateModel = envOrDefault("SEARCH_MISS_TRIAGE_MODEL", properties.getModel());
		SearchMissKeywordInterpreter interpreter = new SearchMissKeywordInterpreter(registry, null, properties);

		List<EvalCase> cases = loadCases();
		int runs = Integer.parseInt(envOrDefault("EVAL_RUNS", String.valueOf(DEFAULT_RUNS)));
		List<ModelSpec> models = modelSpecs(properties.getMaxOutputTokens()).stream()
			.filter(spec -> registry.configuredProviders().contains(spec.ref().provider()))
			.toList();
		assumeThat(models).as("키가 있는 벤더의 모델이 하나도 없다").isNotEmpty();

		Map<ModelSpec, List<Outcome>> outcomes = run(registry, interpreter, models, cases, runs);
		String report = report(outcomes, cases, runs, gateModel);
		writeReport(report, outcomes);
		System.out.println(report);

		outcomes.entrySet().stream()
			.filter(entry -> entry.getKey().label().equals(gateModel))
			.findFirst()
			.ifPresent(entry -> {
				Summary summary = Summary.of(entry.getValue());
				assertThat(summary.critical()).as("운영 모델 %s 의 치명 오류(잘못된 가게로 통과)", gateModel).isZero();
				assertThat(summary.accuracy()).as("운영 모델 %s 의 refersTo 정확도", gateModel)
					.isGreaterThanOrEqualTo(MIN_ACCURACY);
			});
	}

	private Map<ModelSpec, List<Outcome>> run(LlmClientRegistry registry, SearchMissKeywordInterpreter interpreter,
			List<ModelSpec> models, List<EvalCase> cases, int runs) throws Exception {
		int concurrency = Integer.parseInt(envOrDefault("EVAL_CONCURRENCY", String.valueOf(DEFAULT_CONCURRENCY)));
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		try {
			Map<ModelSpec, List<Future<Outcome>>> futures = new LinkedHashMap<>();
			for (ModelSpec model : models) {
				List<Future<Outcome>> modelFutures = new ArrayList<>();
				for (EvalCase evalCase : cases) {
					for (int run = 0; run < runs; run++) {
						modelFutures.add(executor.submit(() -> evaluateOne(registry, interpreter, model, evalCase)));
					}
				}
				futures.put(model, modelFutures);
			}
			Map<ModelSpec, List<Outcome>> outcomes = new LinkedHashMap<>();
			for (Map.Entry<ModelSpec, List<Future<Outcome>>> entry : futures.entrySet()) {
				List<Outcome> modelOutcomes = new ArrayList<>();
				for (Future<Outcome> future : entry.getValue()) {
					modelOutcomes.add(future.get());
				}
				outcomes.put(entry.getKey(), modelOutcomes);
			}
			return outcomes;
		} finally {
			executor.shutdownNow();
		}
	}

	private Outcome evaluateOne(LlmClientRegistry registry, SearchMissKeywordInterpreter interpreter,
			ModelSpec model, EvalCase evalCase) {
		List<NaverBlogPost> posts = evalCase.blogPosts();
		LlmRequest request = new LlmRequest(model.ref().provider(), model.ref().model(),
			interpreter.buildPrompt(evalCase.keyword(), posts), SearchMissKeywordInterpreter.RESPONSE_SCHEMA, 0.0,
			model.maxOutputTokens());
		LlmResult<InterpretAnswer> result = null;
		LlmException lastError = null;
		// 한도 초과·일시 장애는 모델 품질이 아니라 실행 환경 문제라 잠깐 쉬고 다시 부른다
		for (int attempt = 0; attempt < MAX_ATTEMPTS && result == null; attempt++) {
			try {
				result = registry.generateStructured(request, InterpretAnswer.class);
			} catch (LlmException e) {
				lastError = e;
				if (!RETRYABLE.contains(e.getLlmErrorCode())) {
					break;
				}
				sleep(RETRY_BACKOFF_MS * (1L << attempt));
			}
		}
		if (result == null) {
			return Outcome.error(evalCase, describe(lastError));
		}
		InterpretAnswer answer = result.value();
		KeywordInterpretation verified = interpreter.verify(evalCase.keyword(), posts, answer);
		return Outcome.graded(evalCase, answer, verified, result, model.costUsd(result));
	}

	/** 오류 코드만으로는 원인을 못 찾는다 — HTTP 상태와 응답 본문 앞부분을 같이 남긴다(키는 본문에 실리지 않는다) */
	private static String describe(LlmException error) {
		String code = error.getLlmErrorCode().getCode();
		Throwable cause = error.getCause();
		if (cause instanceof RestClientResponseException response) {
			String body = response.getResponseBodyAsString();
			return code + " HTTP " + response.getStatusCode().value() + " "
				+ body.substring(0, Math.min(body.length(), ERROR_BODY_LIMIT)).replace('\n', ' ');
		}
		return cause == null ? code : code + " " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String report(Map<ModelSpec, List<Outcome>> outcomes, List<EvalCase> cases, int runs,
			String gateModel) {
		StringBuilder report = new StringBuilder();
		report.append("## 실패 검색어 해석 평가\n\n")
			.append(String.format(Locale.ROOT, "평가셋 %d건 × %d회. 운영 모델 `%s` 기준: 치명 0, refersTo 정확도 ≥ %.2f%n%n",
				cases.size(), runs, gateModel, MIN_ACCURACY))
			.append("| 모델 | refersTo 정확도 | 파이프라인 정답 | 치명 | 놓침 | 오류 | 지연 p50 / p95 (ms) | 평균 토큰 입력/출력 | 1,000건당 비용 |\n")
			.append("|---|---|---|---|---|---|---|---|---|\n");
		outcomes.entrySet().stream()
			.sorted(Comparator.comparing(entry -> -Summary.of(entry.getValue()).score()))
			.forEach(entry -> {
				Summary summary = Summary.of(entry.getValue());
				String gateMark = entry.getKey().label().equals(gateModel) ? " (운영)" : "";
				report.append(String.format(Locale.ROOT,
					"| `%s`%s | %.2f | %.2f | %d | %d | %d | %d / %d | %d / %d | %s |%n",
					entry.getKey().label(), gateMark, summary.accuracy(), summary.pipelineAccuracy(),
					summary.critical(), summary.missed(), summary.errors(), summary.latencyP50(), summary.latencyP95(),
					summary.avgInputTokens(), summary.avgOutputTokens(), summary.costPerThousand()));
			});

		report.append("\n### 케이스별 (맞힌 횟수 / 실행 횟수, ✗ = 치명 포함)\n\n| 검색어 | 유형 | 정답 |");
		outcomes.keySet().forEach(model -> report.append(" `").append(model.label()).append("` |"));
		report.append("\n|---|---|---|");
		outcomes.keySet().forEach(model -> report.append("---|"));
		report.append('\n');
		for (EvalCase evalCase : cases) {
			report.append("| ").append(evalCase.keyword()).append(" | ").append(evalCase.type()).append(" | ")
				.append(String.join("/", evalCase.expectedRefersTo())).append(" |");
			for (List<Outcome> modelOutcomes : outcomes.values()) {
				List<Outcome> caseOutcomes = modelOutcomes.stream()
					.filter(outcome -> outcome.evalCase() == evalCase)
					.toList();
				long correct = caseOutcomes.stream().filter(Outcome::refersToCorrect).count();
				boolean critical = caseOutcomes.stream().anyMatch(Outcome::critical);
				report.append(' ').append(correct).append('/').append(caseOutcomes.size())
					.append(critical ? " ✗" : "").append(" |");
			}
			report.append('\n');
		}
		report.append("\n### 오류 예시 (모델마다 첫 오류)\n\n");
		outcomes.forEach((model, modelOutcomes) -> modelOutcomes.stream()
			.filter(outcome -> outcome.error() != null)
			.findFirst()
			.ifPresent(outcome -> report.append("- `").append(model.label()).append("`: ")
				.append(outcome.error()).append('\n')));
		return report.toString();
	}

	private static void writeReport(String report, Map<ModelSpec, List<Outcome>> outcomes) throws IOException {
		Files.createDirectories(REPORT_DIR);
		Files.writeString(REPORT_DIR.resolve("report.md"), report, StandardCharsets.UTF_8);
		List<Map<String, Object>> rows = new ArrayList<>();
		outcomes.forEach((model, modelOutcomes) -> modelOutcomes.forEach(outcome -> rows.add(outcome.toRow(model))));
		new ObjectMapper().writerWithDefaultPrettyPrinter()
			.writeValue(REPORT_DIR.resolve("results.json").toFile(), rows);
		String stepSummary = System.getenv("GITHUB_STEP_SUMMARY");
		if (stepSummary != null && !stepSummary.isBlank()) {
			Files.writeString(Path.of(stepSummary), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		}
	}

	private static List<EvalCase> loadCases() throws IOException {
		try (InputStream input = SearchMissInterpreterEvalTest.class.getResourceAsStream(CASES_FILE)) {
			assertThat(input).as("평가셋 파일 %s", CASES_FILE).isNotNull();
			return new ObjectMapper().readValue(input, EvalCaseFile.class).cases();
		}
	}

	private static List<ModelSpec> modelSpecs(int defaultMaxOutputTokens) {
		String configured = System.getenv("EVAL_MODELS");
		List<String> values = configured == null || configured.isBlank()
			? DEFAULT_MODELS
			: Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
		return values.stream().map(value -> ModelSpec.parse(value, defaultMaxOutputTokens)).toList();
	}

	private static String envOrDefault(String name, String defaultValue) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? defaultValue : value;
	}

	/** {@code 벤더:모델[@출력토큰상한]} — 상한을 안 주면 운영 상한을 쓴다. */
	private record ModelSpec(LlmModelRef ref, int maxOutputTokens, String label) {

		static ModelSpec parse(String value, int defaultMaxOutputTokens) {
			String[] parts = value.split("@", 2);
			LlmModelRef ref = LlmModelRef.parse(parts[0]);
			int maxOutputTokens = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : defaultMaxOutputTokens;
			return new ModelSpec(ref, maxOutputTokens, ref.provider() + ":" + ref.model());
		}

		Double costUsd(LlmResult<?> result) {
			double[] price = PRICE_PER_MILLION.get(ref.model());
			if (price == null) {
				return null;
			}
			return (result.inputTokens() * price[0] + result.outputTokens() * price[1]) / 1_000_000.0;
		}
	}

	private record EvalCaseFile(String collectedAt, String source, List<EvalCase> cases) {
	}

	private record EvalCase(
			String keyword,
			String type,
			List<String> expectedRefersTo,
			List<String> expectedNames,
			String note,
			List<Post> posts) {

		boolean expectsStore() {
			return expectedRefersTo.contains("STORE");
		}

		List<NaverBlogPost> blogPosts() {
			return posts.stream().map(post -> new NaverBlogPost(post.title(), post.description(), "")).toList();
		}
	}

	private record Post(String title, String description) {
	}

	/**
	 * 한 번 실행의 채점 결과.
	 *
	 * @param critical 가게가 아닌데 STORE로 통과했거나, 다른 가게 이름으로 통과했다 — 잘못된 가게가 후보에 오른다
	 * @param missed 가게인데 STORE로 통과하지 못했다 — 사람 검토로 넘어가는 안전한 실패
	 */
	private record Outcome(
			EvalCase evalCase,
			String refersTo,
			String officialName,
			KeywordInterpretation.Kind kind,
			boolean refersToCorrect,
			boolean pipelineCorrect,
			boolean critical,
			boolean missed,
			String error,
			long latencyMs,
			int inputTokens,
			int outputTokens,
			Double costUsd) {

		static Outcome graded(EvalCase evalCase, InterpretAnswer answer, KeywordInterpretation verified,
				LlmResult<?> result, Double costUsd) {
			boolean refersToCorrect = evalCase.expectedRefersTo().contains(answer.refersTo());
			boolean passedAsStore = verified.kind() == KeywordInterpretation.Kind.STORE;
			boolean nameMatches = passedAsStore && evalCase.expectedNames().stream()
				.anyMatch(name -> SearchMissTextMatcher.baseName(name)
					.equals(SearchMissTextMatcher.baseName(verified.officialName())));
			boolean critical = passedAsStore && (!evalCase.expectsStore() || !nameMatches);
			boolean missed = evalCase.expectsStore() && !passedAsStore;
			boolean pipelineCorrect = evalCase.expectsStore() ? nameMatches : !passedAsStore;
			return new Outcome(evalCase, answer.refersTo(), answer.officialName(), verified.kind(), refersToCorrect,
				pipelineCorrect, critical, missed, null, result.latencyMs(), result.inputTokens(),
				result.outputTokens(), costUsd);
		}

		static Outcome error(EvalCase evalCase, String error) {
			return new Outcome(evalCase, null, null, null, false, false, false, evalCase.expectsStore(), error, 0L,
				0, 0, null);
		}

		Map<String, Object> toRow(ModelSpec model) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("model", model.label());
			row.put("keyword", evalCase.keyword());
			row.put("expectedRefersTo", evalCase.expectedRefersTo());
			row.put("refersTo", refersTo);
			row.put("officialName", officialName);
			row.put("pipeline", kind == null ? null : kind.name());
			row.put("refersToCorrect", refersToCorrect);
			row.put("critical", critical);
			row.put("missed", missed);
			row.put("error", error);
			row.put("latencyMs", latencyMs);
			row.put("inputTokens", inputTokens);
			row.put("outputTokens", outputTokens);
			return row;
		}
	}

	private record Summary(
			double accuracy,
			double pipelineAccuracy,
			long critical,
			long missed,
			long errors,
			long latencyP50,
			long latencyP95,
			long avgInputTokens,
			long avgOutputTokens,
			String costPerThousand) {

		static Summary of(List<Outcome> outcomes) {
			int total = outcomes.size();
			List<Outcome> answered = outcomes.stream().filter(outcome -> outcome.error() == null).toList();
			List<Long> latencies = answered.stream().map(Outcome::latencyMs).sorted().toList();
			List<Double> costs = answered.stream().map(Outcome::costUsd).toList();
			String costPerThousand = answered.isEmpty() || costs.contains(null)
				? "—"
				: String.format(Locale.ROOT, "$%.3f",
					costs.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 1000);
			return new Summary(
				ratio(outcomes.stream().filter(Outcome::refersToCorrect).count(), total),
				ratio(outcomes.stream().filter(Outcome::pipelineCorrect).count(), total),
				outcomes.stream().filter(Outcome::critical).count(),
				outcomes.stream().filter(Outcome::missed).count(),
				total - answered.size(),
				percentile(latencies, 0.50),
				percentile(latencies, 0.95),
				Math.round(answered.stream().mapToInt(Outcome::inputTokens).average().orElse(0)),
				Math.round(answered.stream().mapToInt(Outcome::outputTokens).average().orElse(0)),
				costPerThousand);
		}

		/** 정렬용: 치명은 정확도보다 무겁게 깎는다 */
		double score() {
			return accuracy - critical * 0.1;
		}

		private static double ratio(long count, int total) {
			return total == 0 ? 0 : (double)count / total;
		}

		private static long percentile(List<Long> sorted, double quantile) {
			if (sorted.isEmpty()) {
				return 0;
			}
			int index = (int)Math.ceil(quantile * sorted.size()) - 1;
			return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
		}
	}
}
