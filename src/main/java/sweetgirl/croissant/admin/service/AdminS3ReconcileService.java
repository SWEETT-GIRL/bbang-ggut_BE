package sweetgirl.croissant.admin.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse.Breakdown;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.S3ObjectSummary;
import sweetgirl.croissant.external.aws.enums.S3Folder;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.repository.S3KeyReferenceJdbcRepository;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

/**
 * S3 와 DB 의 정합성 검사 — DB 가 모르는 S3 객체(주인 없는 파일)를 찾고, 선택적으로 지운다.
 *
 * <p>대상은 가게({@code stores/})·메뉴({@code menus/})·빵 기록({@code bread-records/}) 원본과 그
 * 크기 조정본({@code resized/{320|640|1024}/{원본키}})뿐이다. 그 밖의 폴더는 읽지도 않는다.
 *
 * <p>삭제는 되돌릴 수 없다(버킷 버전 관리가 꺼져 있다). 그래서
 * <ul>
 * <li>기본은 dry-run 이고,</li>
 * <li>생긴 지 {@code minAgeDays} 가 안 된 객체는 지우지 않으며 — 방금 올리고 아직 등록하지 않은 파일, 처리 중인 파일 —</li>
 * <li>삭제 대상이 {@code maxDelete} 를 넘으면 하나도 지우지 않고 수치만 돌려주고,</li>
 * <li>대상 폴더의 키를 담을 수 있는 다른 테이블까지 전부 읽어 참조되는 파일은 남긴다.</li>
 * </ul>
 *
 * <p>트랜잭션을 걸지 않는다 — S3 목록 조회가 분 단위로 걸리는 동안 DB 커넥션을 쥐고 있지 않기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminS3ReconcileService {

	public static final int DEFAULT_MIN_AGE_DAYS = 7;
	public static final int DEFAULT_MAX_DELETE = 1000;

	private static final int SAMPLE_LIMIT = 100;
	private static final int UPDATE_BATCH_SIZE = 1000;
	private static final List<S3Folder> TARGET_FOLDERS = List.of(
		S3Folder.STORES, S3Folder.MENUS, S3Folder.BREAD_RECORDS);
	private static final List<Integer> RESIZED_WIDTHS = List.of(320, 640, 1024);
	private static final Pattern RESIZED_KEY = Pattern.compile("^resized/\\d+/(.+)$");
	private static final String KIND_ORIGINAL = "ORIGINAL";
	private static final String KIND_RESIZED = "RESIZED";

	private final S3Service s3Service;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final S3KeyReferenceJdbcRepository s3KeyReferenceJdbcRepository;

	/** 전체 검사는 수 분이 걸린다. 겹쳐 돌면 같은 파일을 두 번 세고 지우므로 한 번에 하나만 돈다. */
	private final AtomicBoolean running = new AtomicBoolean();
	/** 요청이 프록시 제한 시간을 넘겨 응답을 못 받아도 결과를 다시 읽을 수 있게 마지막 결과를 들고 있는다. */
	private final AtomicReference<AdminS3ReconcileResponse> latestResult = new AtomicReference<>();

	/** 전체 폴더를 기본 안전장치 값으로 검사한다. */
	public AdminS3ReconcileResponse reconcile(boolean dryRun) {
		return reconcile(dryRun, null, DEFAULT_MIN_AGE_DAYS, DEFAULT_MAX_DELETE);
	}

	/**
	 * @param scope      검사할 폴더({@code stores}·{@code menus}·{@code bread-records}). 비우면 셋 다
	 * @param minAgeDays 이 일수보다 최근에 생긴 객체는 지우지 않는다 (1 이상)
	 * @param maxDelete  삭제 대상이 이 수를 넘으면 하나도 지우지 않는다. S3 에 없는 이미지의 FAILED 처리에도 같은 상한을 쓴다
	 */
	public AdminS3ReconcileResponse reconcile(boolean dryRun, String scope, int minAgeDays, int maxDelete) {
		List<S3Folder> folders = resolveFolders(scope);
		if (minAgeDays < 1 || maxDelete < 0) {
			throw new S3StorageException(S3ErrorCode.RECONCILE_INVALID_OPTION);
		}
		if (!running.compareAndSet(false, true)) {
			throw new S3StorageException(S3ErrorCode.RECONCILE_ALREADY_RUNNING);
		}
		try {
			AdminS3ReconcileResponse response = run(dryRun, folders, minAgeDays, maxDelete);
			latestResult.set(response);
			return response;
		} finally {
			running.set(false);
		}
	}

	/** 이 서버에서 마지막으로 끝난 검사 결과. 재시작하면 사라진다. */
	public Optional<AdminS3ReconcileResponse> findLatestResult() {
		return Optional.ofNullable(latestResult.get());
	}

	private AdminS3ReconcileResponse run(boolean dryRun, List<S3Folder> folders, int minAgeDays, int maxDelete) {
		Instant startedAt = Instant.now();
		Tally tally = new Tally(startedAt.minus(Duration.ofDays(minAgeDays)));

		// DB 를 먼저 읽고 S3 를 나중에 읽는다. 반대로 하면 그 사이에 등록된 이미지가 "S3 에 없음"으로 잡힌다.
		// DB 를 읽은 뒤에 올라온 파일은 주인 없는 것으로 보이지만 최근 생성분이라 삭제 대상에서 빠진다.
		Map<S3Folder, Set<String>> protectedKeys = loadProtectedKeys();
		for (S3Folder folder : folders) {
			scanFolder(folder, protectedKeys.get(folder), tally);
		}

		int deletedCount = 0;
		int failedMarkedCount = 0;
		boolean deleteAborted = false;
		boolean failedMarkAborted = false;
		if (!dryRun) {
			deleteAborted = tally.deletableKeys.size() > maxDelete;
			if (!deleteAborted && !tally.deletableKeys.isEmpty()) {
				deletedCount = s3Service.deleteObjects(tally.deletableKeys);
			}
			// 목록 조회가 잘못돼 멀쩡한 이미지가 통째로 "없음"으로 잡혔을 때 전부 숨기는 일을 같은 상한으로 막는다.
			failedMarkAborted = tally.missingStoreKeys.size() + tally.missingMenuKeys.size() > maxDelete;
			if (!failedMarkAborted) {
				failedMarkedCount = markMissingAsFailed(tally.missingStoreKeys, tally.missingMenuKeys);
			}
		}

		AdminS3ReconcileResponse response = AdminS3ReconcileResponse.builder()
			.dryRun(dryRun)
			.s3KeyCount(tally.s3KeyCount)
			.dbKeyCount(tally.dbKeyCount)
			.orphanCount(tally.orphanCount)
			.deletedCount(deletedCount)
			.orphanKeys(List.copyOf(tally.orphanSample))
			.missingCount(tally.missingCount)
			.failedMarkedCount(failedMarkedCount)
			.missingKeys(List.copyOf(tally.missingSample))
			.scopes(folders.stream().map(S3Folder::getValue).toList())
			.minAgeDays(minAgeDays)
			.maxDelete(maxDelete)
			.resizedKeyCount(tally.resizedKeyCount)
			.orphanBytes(tally.orphanBytes)
			.deletableCount(tally.deletableKeys.size())
			.deletableBytes(tally.deletableBytes)
			.recentSkippedCount(tally.recentSkippedCount)
			.protectedCount(tally.protectedCount)
			.deleteAborted(deleteAborted)
			.failedMarkAborted(failedMarkAborted)
			.breakdown(tally.toBreakdown())
			.startedAt(startedAt)
			.durationMs(Duration.between(startedAt, Instant.now()).toMillis())
			.build();
		logResult(response);
		return response;
	}

	private void scanFolder(S3Folder folder, Set<String> protectedKeys, Tally tally) {
		// 키 → S3 에서 원본을 봤는지. 폴더 하나씩 올렸다 내려 메모리에 한 폴더 분량만 둔다.
		Map<String, Boolean> ownedKeys = loadOwnedKeys(folder);
		tally.dbKeyCount += ownedKeys.size();

		s3Service.forEachObject(folder.prefix(), object -> {
			if (object.key().endsWith("/")) {
				return;
			}
			tally.s3KeyCount++;
			if (ownedKeys.replace(object.key(), Boolean.TRUE) != null) {
				return;
			}
			if (protectedKeys.contains(object.key())) {
				tally.protectedCount++;
				return;
			}
			tally.addOrphan(folder, KIND_ORIGINAL, object);
		});

		// 크기 조정본은 원본의 주인을 따라간다 — 원본을 DB 가 알면 크기 조정본이 일부만 있어도 그대로 둔다.
		for (int width : RESIZED_WIDTHS) {
			String resizedPrefix = S3PathGenerator.getResizedKey(width, "");
			s3Service.forEachObject(resizedPrefix + folder.prefix(), object -> {
				tally.resizedKeyCount++;
				String originalKey = object.key().substring(resizedPrefix.length());
				if (ownedKeys.containsKey(originalKey)) {
					return;
				}
				if (protectedKeys.contains(originalKey)) {
					tally.protectedCount++;
					return;
				}
				tally.addOrphan(folder, KIND_RESIZED, object);
			});
		}

		ownedKeys.forEach((key, seenInS3) -> {
			if (!seenInS3) {
				tally.addMissing(folder, key);
			}
		});
	}

	/**
	 * 폴더의 주 테이블이 아는 원본 키.
	 *
	 * <p>가게·메뉴는 엔티티의 {@code @SQLRestriction} 때문에 처리 완료(DONE) 행만 온다 — S3 에 없을 때
	 * FAILED 로 숨길 대상이 정확히 이 행들이다. 처리 중·실패 행은 {@link #loadProtectedKeys()} 가 따로 지킨다.
	 */
	private Map<String, Boolean> loadOwnedKeys(S3Folder folder) {
		List<String> keys = switch (folder) {
			case STORES -> storeImageFileRepository.findAllAwsKeys();
			case MENUS -> menuImageFileRepository.findAllAwsKeys();
			case BREAD_RECORDS -> s3KeyReferenceJdbcRepository.findBreadRecordPhotoKeys();
			default -> throw new S3StorageException(S3ErrorCode.RECONCILE_INVALID_OPTION);
		};
		Map<String, Boolean> ownedKeys = new HashMap<>((int)(keys.size() / 0.75f) + 1);
		for (String key : keys) {
			// 다른 폴더의 키를 든 행은 이 폴더 목록에 나올 수 없다. "S3 에 없음"으로 세지 않고 보호 키로만 다룬다.
			if (key.startsWith(folder.prefix())) {
				ownedKeys.put(key, Boolean.FALSE);
			}
		}
		return ownedKeys;
	}

	/** 주 테이블의 정상 행 말고 대상 폴더의 파일을 가리키는 값 전부를, 원본 키로 바꿔 폴더별로 나눈다. */
	private Map<S3Folder, Set<String>> loadProtectedKeys() {
		Map<S3Folder, Set<String>> protectedKeys = new EnumMap<>(S3Folder.class);
		TARGET_FOLDERS.forEach(folder -> protectedKeys.put(folder, new HashSet<>()));
		for (String referencedKey : s3KeyReferenceJdbcRepository.findProtectedKeys()) {
			Matcher resized = RESIZED_KEY.matcher(referencedKey);
			String originalKey = resized.matches() ? resized.group(1) : referencedKey;
			TARGET_FOLDERS.stream()
				.filter(folder -> originalKey.startsWith(folder.prefix()))
				.findFirst()
				.ifPresent(folder -> protectedKeys.get(folder).add(originalKey));
		}
		return protectedKeys;
	}

	private List<S3Folder> resolveFolders(String scope) {
		if (scope == null || scope.isBlank()) {
			return TARGET_FOLDERS;
		}
		return TARGET_FOLDERS.stream()
			.filter(folder -> folder.getValue().equals(scope.strip()))
			.findFirst()
			.map(List::of)
			.orElseThrow(() -> new S3StorageException(S3ErrorCode.RECONCILE_INVALID_OPTION));
	}

	private int markMissingAsFailed(List<String> storeKeys, List<String> menuKeys) {
		int updated = 0;
		for (int start = 0; start < storeKeys.size(); start += UPDATE_BATCH_SIZE) {
			updated += storeImageFileRepository.updateStatusByAwsKeyInAndStatus(
				storeKeys.subList(start, Math.min(start + UPDATE_BATCH_SIZE, storeKeys.size())),
				ImageStatus.DONE,
				ImageStatus.FAILED);
		}
		for (int start = 0; start < menuKeys.size(); start += UPDATE_BATCH_SIZE) {
			updated += menuImageFileRepository.updateStatusByAwsKeyInAndStatus(
				menuKeys.subList(start, Math.min(start + UPDATE_BATCH_SIZE, menuKeys.size())),
				ImageStatus.DONE,
				ImageStatus.FAILED);
		}
		return updated;
	}

	private void logResult(AdminS3ReconcileResponse response) {
		String summary = "[reconcile] dryRun={} scopes={} s3Keys={} resizedKeys={} dbKeys={} orphans={} orphanBytes={} "
			+ "deletable={} recentSkipped={} protected={} deleted={} deleteAborted={} missing={} failedMarked={} "
			+ "failedMarkAborted={} durationMs={}";
		Object[] values = {response.dryRun(), response.scopes(), response.s3KeyCount(), response.resizedKeyCount(),
			response.dbKeyCount(), response.orphanCount(), response.orphanBytes(), response.deletableCount(),
			response.recentSkippedCount(), response.protectedCount(), response.deletedCount(),
			response.deleteAborted(), response.missingCount(), response.failedMarkedCount(),
			response.failedMarkAborted(), response.durationMs()};
		if (response.dryRun()) {
			log.info(summary, values);
		} else {
			log.warn(summary, values);
		}
	}

	/** 검사 한 번의 집계. 주인 없는 키는 삭제 대상만 전부 들고, 응답에는 표본만 싣는다. */
	private static final class Tally {

		private final Instant deletableBefore;
		private final Map<String, long[]> breakdown = new LinkedHashMap<>();
		private final List<String> deletableKeys = new ArrayList<>();
		private final List<String> orphanSample = new ArrayList<>();
		private final List<String> missingSample = new ArrayList<>();
		private final List<String> missingStoreKeys = new ArrayList<>();
		private final List<String> missingMenuKeys = new ArrayList<>();
		private int s3KeyCount;
		private int resizedKeyCount;
		private int dbKeyCount;
		private int orphanCount;
		private long orphanBytes;
		private long deletableBytes;
		private int recentSkippedCount;
		private int protectedCount;
		private int missingCount;

		private Tally(Instant deletableBefore) {
			this.deletableBefore = deletableBefore;
		}

		private void addOrphan(S3Folder folder, String kind, S3ObjectSummary object) {
			// 칸: 주인 없는 수, 주인 없는 용량, 삭제 대상 수, 삭제 대상 용량
			long[] cells = breakdown.computeIfAbsent(folder.getValue() + "|" + kind, key -> new long[4]);
			orphanCount++;
			orphanBytes += object.size();
			cells[0]++;
			cells[1] += object.size();
			if (orphanSample.size() < SAMPLE_LIMIT) {
				orphanSample.add(object.key());
			}
			// 수정 시각을 모르면 최근 것으로 본다 — 확인하지 못한 것은 지우지 않는다.
			if (object.lastModified() == null || !object.lastModified().isBefore(deletableBefore)) {
				recentSkippedCount++;
				return;
			}
			deletableKeys.add(object.key());
			deletableBytes += object.size();
			cells[2]++;
			cells[3] += object.size();
		}

		private void addMissing(S3Folder folder, String key) {
			missingCount++;
			if (missingSample.size() < SAMPLE_LIMIT) {
				missingSample.add(key);
			}
			// 빵 기록 사진은 처리 상태로 숨기지 않는 테이블이라 세기만 한다.
			if (folder == S3Folder.STORES) {
				missingStoreKeys.add(key);
			} else if (folder == S3Folder.MENUS) {
				missingMenuKeys.add(key);
			}
		}

		private List<Breakdown> toBreakdown() {
			return breakdown.entrySet().stream()
				.map(entry -> {
					String[] scopeAndKind = entry.getKey().split("\\|");
					long[] cells = entry.getValue();
					return new Breakdown(scopeAndKind[0], scopeAndKind[1],
						(int)cells[0], cells[1], (int)cells[2], cells[3]);
				})
				.toList();
		}
	}
}
