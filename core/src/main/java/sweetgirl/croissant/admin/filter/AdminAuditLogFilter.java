package sweetgirl.croissant.admin.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.entity.AdminAuditLog;
import sweetgirl.croissant.admin.service.AdminAuditLogCommandService;
import sweetgirl.croissant.global.logging.SensitiveUrlMasker;
import sweetgirl.croissant.global.logging.filter.RequestIdFilter;
import sweetgirl.croissant.letter.config.AdminApiKeyFilter;

/**
 * 어드민 API 의 쓰기(POST·PUT·PATCH·DELETE)와 회원 개인 정보 조회(GET /api/admin/members…)를 {@code admin_audit_logs} 에 남긴다.
 *
 * <p>{@link AdminApiKeyFilter} 바로 뒤에서 돈다 — 열쇠를 통과한 요청만 남는다. 열쇠 원문은 남기지 않는다: 설정한 이름
 * ({@code admin.audit.key-label})이 있으면 그것, 없으면 열쇠 SHA-256 의 앞 8자리.
 *
 * <p>요청 바디는 JSON 이면 비밀번호·토큰·키·전화·이메일 필드를 {@code ***} 로 가리고 2KB 로 자른다. JSON 이 아니면(멀티파트 등)
 * 크기만 남긴다. 저장은 따로 커밋하고, 실패해도 경고만 남긴다 — 감사 로그 때문에 어드민 요청이 실패하면 안 된다.
 */
@Slf4j
public class AdminAuditLogFilter extends OncePerRequestFilter {

	static final int SUMMARY_MAX = 2048;
	static final String MASK = "***";

	private static final int BODY_CACHE_LIMIT = 64 * 1024;
	private static final List<String> ADMIN_PREFIXES = List.of("/api/admin/", "/admin/");
	private static final List<String> PII_READ_PREFIXES = List.of("/api/admin/members");
	private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
	private static final List<String> SENSITIVE_WORDS = List.of(
		"password", "passwd", "token", "secret", "phone", "email");
	private static final String UUID_PATTERN = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
	/** id 로 보는 칸: 숫자 · UUID · 월(2026-10) · 날짜(2026-10-16). */
	private static final Pattern ID_SEGMENT = Pattern.compile(
		"^\\d+$|^" + UUID_PATTERN + "$|^\\d{4}-\\d{2}(-\\d{2})?$");
	private static final Map<String, String> TARGET_TYPES = Map.ofEntries(
		Map.entry("members", "MEMBER"),
		Map.entry("courses", "COURSE"),
		Map.entry("stores", "STORE"),
		Map.entry("menus", "MENU"),
		Map.entry("notifications", "PUSH"),
		Map.entry("rewards", "REWARD"),
		Map.entry("letters", "LETTER"),
		Map.entry("banners", "NOTICE"),
		Map.entry("reports", "REPORT"),
		Map.entry("world", "WORLD"),
		Map.entry("monthly-reports", "MONTHLY_REPORT"));

	private final AdminAuditLogCommandService commandService;
	private final ObjectMapper objectMapper;
	private final String configuredLabel;

	public AdminAuditLogFilter(AdminAuditLogCommandService commandService, ObjectMapper objectMapper,
			String configuredLabel) {
		this.commandService = commandService;
		this.objectMapper = objectMapper;
		this.configuredLabel = configuredLabel;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (ADMIN_PREFIXES.stream().noneMatch(uri::startsWith)) {
			return true;
		}
		if (!READ_METHODS.contains(request.getMethod())) {
			return false;
		}
		return PII_READ_PREFIXES.stream().noneMatch(uri::startsWith);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, BODY_CACHE_LIMIT);
		boolean failed = true;
		try {
			chain.doFilter(wrapped, response);
			failed = false;
		} finally {
			record(wrapped, failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus());
		}
	}

	private void record(ContentCachingRequestWrapper request, int status) {
		try {
			String path = SensitiveUrlMasker.maskPath(request.getRequestURI());
			String[] target = target(path);
			commandService.record(AdminAuditLog.builder()
				.adminLabel(label(request.getHeader(AdminApiKeyFilter.HEADER)))
				.method(request.getMethod())
				.path(truncate(path, 255))
				.query(truncate(SensitiveUrlMasker.maskQuery(request.getQueryString()), 500))
				.targetType(target[0])
				.targetId(target[1])
				.requestSummary(summarize(request))
				.statusCode(status)
				.ip(truncate(clientIp(request), 45))
				.userAgent(truncate(request.getHeader("User-Agent"), 255))
				.requestId(MDC.get(RequestIdFilter.MDC_REQUEST_ID))
				.createdAt(Instant.now())
				.build());
		} catch (RuntimeException exception) {
			log.warn("[어드민 감사 로그] 저장 실패 method={} uri={} cause={}",
				request.getMethod(), request.getRequestURI(), exception.toString());
		}
	}

	String label(String providedKey) {
		if (StringUtils.hasText(configuredLabel)) {
			return truncate(configuredLabel.strip(), 50);
		}
		if (!StringUtils.hasText(providedKey)) {
			return "no-key";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(providedKey.strip().getBytes(StandardCharsets.UTF_8));
			return "key:" + HexFormat.of().formatHex(digest).substring(0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}

	/** {@code /api/admin/members/12/ban} → MEMBER · 12. 모르는 자원은 이름을 대문자로, id 처럼 생긴 다음 칸이 없으면 id 는 비운다. */
	static String[] target(String path) {
		String rest = ADMIN_PREFIXES.stream().filter(path::startsWith).findFirst()
			.map(prefix -> path.substring(prefix.length()))
			.orElse("");
		String[] segments = rest.split("/");
		if (segments.length == 0 || segments[0].isBlank()) {
			return new String[] {null, null};
		}
		String type = TARGET_TYPES.getOrDefault(segments[0],
			truncate(segments[0].replace('-', '_').toUpperCase(Locale.ROOT), 50));
		String id = segments.length > 1 && ID_SEGMENT.matcher(segments[1]).matches() ? segments[1] : null;
		return new String[] {type, id};
	}

	private String summarize(ContentCachingRequestWrapper request) {
		byte[] body = request.getContentAsByteArray();
		if (body.length == 0) {
			return null;
		}
		String contentType = request.getContentType();
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE)) {
			return "(JSON 아님 · " + body.length + " bytes)";
		}
		try {
			JsonNode root = objectMapper.readTree(body);
			mask(root);
			return truncate(objectMapper.writeValueAsString(root), SUMMARY_MAX);
		} catch (IOException e) {
			return "(읽을 수 없는 JSON · " + body.length + " bytes)";
		}
	}

	static void mask(JsonNode node) {
		if (node instanceof ObjectNode object) {
			Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (isSensitive(field.getKey())) {
					field.setValue(object.textNode(MASK));
				} else {
					mask(field.getValue());
				}
			}
		} else if (node instanceof ArrayNode array) {
			array.forEach(AdminAuditLogFilter::mask);
		}
	}

	static boolean isSensitive(String fieldName) {
		String name = fieldName.toLowerCase(Locale.ROOT);
		return name.endsWith("key") || SENSITIVE_WORDS.stream().anyMatch(name::contains);
	}

	private static String clientIp(HttpServletRequest request) {
		String realIp = request.getHeader("X-Real-IP");
		return StringUtils.hasText(realIp) ? realIp.strip() : request.getRemoteAddr();
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}
}
