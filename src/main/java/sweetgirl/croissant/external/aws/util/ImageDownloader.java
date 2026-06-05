package sweetgirl.croissant.external.aws.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.enums.MimeType;

@Slf4j
@Component
public class ImageDownloader {

	private static final String NAVER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
		+ "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
	private static final String NAVER_REFERER = "https://pcmap.place.naver.com/";
	private static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	private static final Map<String, MimeType> EXTENSION_MIME = Map.of(
		"jpg", MimeType.IMAGE_JPEG,
		"jpeg", MimeType.IMAGE_JPEG,
		"png", MimeType.IMAGE_PNG,
		"webp", MimeType.IMAGE_WEBP,
		"heic", MimeType.IMAGE_HEIC);

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public Result download(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			return Result.failure("invalid url");
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", NAVER_UA)
			.GET();
		if (needsNaverReferer(uri)) {
			builder.header("Referer", NAVER_REFERER);
		}

		HttpResponse<byte[]> response;
		try {
			response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException e) {
			return Result.failure("io error: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.failure("interrupted");
		}

		if (response.statusCode() / 100 != 2) {
			return Result.failure("status " + response.statusCode());
		}

		byte[] body = response.body();
		if (body == null || body.length == 0) {
			return Result.failure("empty body");
		}
		if (body.length > MAX_BYTES) {
			return Result.failure("too large (" + body.length + " bytes)");
		}

		MimeType mimeType = resolveMimeType(response, uri);
		return Result.success(body, mimeType);
	}

	private boolean needsNaverReferer(URI uri) {
		String host = uri.getHost();
		return host != null && host.contains("pstatic");
	}

	private MimeType resolveMimeType(HttpResponse<byte[]> response, URI uri) {
		String contentType = response.headers().firstValue("content-type").orElse("");
		if (!contentType.isBlank()) {
			String normalized = contentType.split(";", 2)[0].trim().toLowerCase();
			try {
				return MimeType.fromString(normalized);
			} catch (RuntimeException ignored) {
				// header 값이 허용 목록 밖이면 URL 확장자로 폴백
			}
		}
		String ext = extractExtension(uri);
		return EXTENSION_MIME.getOrDefault(ext, MimeType.IMAGE_JPEG);
	}

	private String extractExtension(URI uri) {
		String inner = innerSrcParam(uri);
		String target = inner != null ? inner : uri.getPath();
		if (target == null) {
			return "";
		}
		int queryStart = target.indexOf('?');
		if (queryStart >= 0) {
			target = target.substring(0, queryStart);
		}
		int dot = target.lastIndexOf('.');
		if (dot < 0 || dot == target.length() - 1) {
			return "";
		}
		return target.substring(dot + 1).toLowerCase();
	}

	private String innerSrcParam(URI uri) {
		String query = uri.getRawQuery();
		if (query == null) {
			return null;
		}
		for (String part : query.split("&")) {
			if (part.startsWith("src=")) {
				try {
					return new URI(part.substring(4)).toString();
				} catch (URISyntaxException e) {
					return null;
				}
			}
		}
		return null;
	}

	public static final class Result {
		private final byte[] bytes;
		private final MimeType mimeType;
		private final String failureReason;

		private Result(byte[] bytes, MimeType mimeType, String failureReason) {
			this.bytes = bytes;
			this.mimeType = mimeType;
			this.failureReason = failureReason;
		}

		public static Result success(byte[] bytes, MimeType mimeType) {
			return new Result(bytes, mimeType, null);
		}

		public static Result failure(String reason) {
			return new Result(null, null, reason);
		}

		public boolean isSuccess() {
			return failureReason == null;
		}

		public byte[] bytes() {
			return bytes;
		}

		public MimeType mimeType() {
			return mimeType;
		}

		public String failureReason() {
			return failureReason;
		}
	}
}
