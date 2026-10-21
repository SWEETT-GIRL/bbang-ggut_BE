package sweetgirl.dataops.candidate.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.candidate.enums.CandidateRegistrationState;
import sweetgirl.dataops.candidate.enums.CandidateStatus;
import sweetgirl.dataops.common.entity.BaseTimeEntity;

/**
 * 공공데이터(LOCALDATA) 신규 인허가 후보 — 4단계 신규 가게 유입 큐.
 * 폐업은 change_proposal(target=store.status) 로 가고, 이 테이블은 신규 등록 전용이다.
 */
@Entity
@Table(name = "new_store_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewStoreCandidate extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "mgmt_no", nullable = false, length = 60)
	private String mgmtNo;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "road_address", length = 200)
	private String roadAddress;

	@Column(name = "jibun_address", length = 200)
	private String jibunAddress;

	@Column(name = "category_code", length = 40)
	private String categoryCode;

	@Column(name = "licensed_at")
	private LocalDate licensedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CandidateStatus status;

	@Column(name = "naver_place_id", length = 50)
	private String naverPlaceId;

	@Column(name = "match_attempts", nullable = false)
	private int matchAttempts;

	@Column(name = "registered_store_id")
	private Long registeredStoreId;

	/** 후속까지 끝났는지 — status 의 REGISTERED 와 분리한다 (v3 P1-3) */
	@Enumerated(EnumType.STRING)
	@Column(name = "registration_state", length = 30)
	private CandidateRegistrationState registrationState;

	/** 운영 INSERT 이전에 발급하는 최소 operation 식별자 — 결과 불명을 확인 없이 다시 쓰지 않기 위한 표지 */
	@Column(name = "registration_operation_id")
	private UUID registrationOperationId;

	@Column(name = "registration_note", length = 300)
	private String registrationNote;

	/**
	 * 위치 근거가 검증됐는지 — 인허가 지번주소에서 3토큰을 도출한 것은 검증이 아니다.
	 * P4-4 공통 resolver 전까지, 이미 만든 가게의 후속 복구는 이 값을 true 로 바꾸지 않는다.
	 */
	@Column(name = "location_evidence_verified", nullable = false)
	private boolean locationEvidenceVerified;

	@Builder
	public NewStoreCandidate(String mgmtNo, String name, String roadAddress, String jibunAddress,
			String categoryCode, LocalDate licensedAt, CandidateStatus status, String naverPlaceId) {
		this.mgmtNo = mgmtNo;
		this.name = name;
		this.roadAddress = roadAddress;
		this.jibunAddress = jibunAddress;
		this.categoryCode = categoryCode;
		this.licensedAt = licensedAt;
		this.status = status != null ? status : CandidateStatus.WAITING_NAVER;
		this.naverPlaceId = naverPlaceId;
	}

	/** 운영 쓰기 전 — operation 을 발급해 두면 응답을 잃어도 이 후보가 시도됐음이 남는다 */
	public void prepareRegistration(UUID operationId) {
		this.registrationOperationId = operationId;
		this.registrationState = CandidateRegistrationState.PREPARED;
		this.registrationNote = null;
	}

	/**
	 * 운영 store 존재 확인 — 신규 INSERT 든 기존 가게 발견이든 여기까지다. 아직 등록 완료가 아니다.
	 * @param locationEvidenceVerified 신규 INSERT 에 검증된 위치 근거가 실렸는지. 기존 가게 귀속은 위치를 쓰지 않으므로 false
	 */
	public void markStorePresent(long storeId, boolean locationEvidenceVerified) {
		this.registeredStoreId = storeId;
		this.registrationState = CandidateRegistrationState.STORE_PRESENT;
		if (locationEvidenceVerified) {
			this.locationEvidenceVerified = true;
		}
	}

	/** 후속(sync_state·외부 ID·초기 수집 예약)까지 확인됐다 */
	public void completeRegistration() {
		this.status = CandidateStatus.REGISTERED;
		this.registrationState = CandidateRegistrationState.COMPLETED;
		this.registrationNote = null;
	}

	/** 운영에는 가게가 있으나 후속이 빠졌다 — 재실행으로 보완한다 */
	public void markNeedsRecovery(String note) {
		this.status = CandidateStatus.REGISTERED;
		this.registrationState = CandidateRegistrationState.NEEDS_RECOVERY;
		this.registrationNote = truncate(note);
	}

	/** 운영 결과 불명 — status 는 MATCHED 로 두지 않는다. 확인 전에는 다시 쓰지 않기 위해서다 */
	public void markRegistrationUnknown(String note) {
		this.registrationState = CandidateRegistrationState.UNKNOWN;
		this.registrationNote = truncate(note);
	}

	/** 보류 — 근거가 갖춰지면 다시 시도할 수 있게 MATCHED 로 남긴다 */
	public void hold(CandidateRegistrationState state, String note) {
		this.registrationState = state;
		this.registrationNote = truncate(note);
	}

	public void markRejected() {
		this.status = CandidateStatus.REJECTED;
	}

	private static String truncate(String note) {
		return note == null || note.length() <= 300 ? note : note.substring(0, 300);
	}
}
