package sweetgirl.croissant.external.aws.metrics;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;

/**
 * 이미지 처리 파이프라인 관측 지표.
 *
 * <p>업로드된 원본을 Lambda가 리사이즈하고 콜백으로 알려주는 구조라, 중간이 끊겨도
 * 사용자가 "사진이 안 떠요"라고 말하기 전까지 알 방법이 없었다. 다음 두 가지를 구분해서 본다.
 *
 * <ul>
 *   <li>{@code FAILED} — Lambda가 살아서 "못 하겠다"고 답한 것. 깨진 파일이거나 지원하지 않는 포맷</li>
 *   <li>{@code PENDING} 적체 — 아무 답이 없는 것. 처리 중일 수도, <b>Lambda가 죽었을 수도</b> 있다</li>
 * </ul>
 *
 * <p>파이프라인이 통째로 멈추면 {@code FAILED}는 하나도 늘지 않는다. 실패 통지조차 오지 않기 때문이다.
 * 그래서 <b>적체 게이지가 실패율보다 중요한 신호</b>다.
 */
@Component
@RequiredArgsConstructor
public class ImagePipelineMetrics {

	private static final String CALLBACK_METRIC = "image.callback";
	private static final String ORPHAN_CALLBACK_METRIC = "image.orphan.callback";
	private static final String PENDING_CALLBACKS_METRIC = "image.pending.callbacks";
	private static final String PROCESSING_PENDING_METRIC = "image.processing.pending";
	private static final String S3_ORPHAN_METRIC = "s3.orphan.objects";
	private static final String S3_MISSING_METRIC = "s3.missing.objects";

	/** 콜백에 MIME 정보가 없거나 반영할 이미지 행을 찾지 못했을 때 쓰는 태그 값. */
	private static final String UNKNOWN_MIME = "unknown";

	private final MeterRegistry meterRegistry;

	// Gauge는 관측 대상을 약한 참조로 잡으므로 필드로 보관해 GC 되지 않게 한다.
	private final AtomicLong pendingCallbacks = new AtomicLong();
	private final AtomicLong processingPending = new AtomicLong();
	private final AtomicLong s3OrphanObjects = new AtomicLong();
	private final AtomicLong s3MissingObjects = new AtomicLong();

	/**
	 * Counter는 최초 증가 전까지 {@code /actuator/prometheus}에 노출되지 않는다.
	 * 대시보드에서 "0건"과 "계측이 안 됨"을 구분할 수 있도록 부팅 시 시계열을 미리 등록한다.
	 */
	@PostConstruct
	void registerMeters() {
		for (ImageStatus status : new ImageStatus[] {ImageStatus.DONE, ImageStatus.FAILED}) {
			for (MimeType mimeType : MimeType.values()) {
				callbackCounter(status, mimeType.name());
			}
			callbackCounter(status, UNKNOWN_MIME);
		}
		orphanCallbackCounter();

		gauge(PENDING_CALLBACKS_METRIC, pendingCallbacks,
			"이미지 행을 아직 찾지 못해 보류 중인 콜백 수 — 늘어나면 등록 API 실패나 경쟁 상태 재발을 의심한다");
		gauge(PROCESSING_PENDING_METRIC, processingPending,
			"리사이즈 결과를 기다리는 이미지 수 — 계속 증가하면 Lambda 정지 신호다");
		gauge(S3_ORPHAN_METRIC, s3OrphanObjects,
			"DB에 행이 없는 S3 객체 수 (야간 배치로 하루 1회 갱신)");
		gauge(S3_MISSING_METRIC, s3MissingObjects,
			"DB에는 있으나 S3에 없는 객체 수 — 사용자에게 깨진 이미지가 보이는 상태이므로 0이어야 한다");
	}

	/** 콜백 수신. 반영할 이미지 행을 찾아 MIME을 알 수 있을 때만 태그에 담는다. */
	public void recordCallback(ImageStatus status, MimeType mimeType) {
		callbackCounter(status, mimeType == null ? UNKNOWN_MIME : mimeType.name()).increment();
	}

	/**
	 * 이미지 행보다 콜백이 먼저 도착한 경우.
	 *
	 * <p>보류 테이블에 저장되므로 유실되지는 않지만, 급증하면 등록 API가 실패하고 있다는 뜻이다.
	 */
	public void recordOrphanCallback() {
		orphanCallbackCounter().increment();
	}

	public void updatePendingCallbacks(long value) {
		pendingCallbacks.set(value);
	}

	public void updateProcessingPending(long value) {
		processingPending.set(value);
	}

	public void updateS3Reconcile(long orphans, long missing) {
		s3OrphanObjects.set(orphans);
		s3MissingObjects.set(missing);
	}

	private Counter callbackCounter(ImageStatus status, String mimeType) {
		return Counter.builder(CALLBACK_METRIC)
			.description("Lambda 리사이즈 콜백 수신 수")
			.tag("status", status.name().toLowerCase(Locale.ROOT))
			// GIF처럼 리사이즈 대상이 아니라 의도적으로 실패하는 포맷을 구분하기 위해 함께 남긴다.
			.tag("mime_type", mimeType.toLowerCase(Locale.ROOT))
			.register(meterRegistry);
	}

	private Counter orphanCallbackCounter() {
		return Counter.builder(ORPHAN_CALLBACK_METRIC)
			.description("이미지 행 생성보다 먼저 도착해 보류된 콜백 수")
			.register(meterRegistry);
	}

	private void gauge(String name, AtomicLong holder, String description) {
		Gauge.builder(name, holder, AtomicLong::get)
			.description(description)
			.register(meterRegistry);
	}
}
