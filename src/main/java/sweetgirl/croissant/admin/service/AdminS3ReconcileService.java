package sweetgirl.croissant.admin.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.enums.S3Folder;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

// S3 와 DB 의 정합성 검사 — DB awsKey 에 없는 S3 객체(orphan)를 식별하고 선택적으로 삭제.
// Saga 보상 실패 등으로 남을 수 있는 dangling 객체를 nightly cleanup 용으로 정리.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminS3ReconcileService {

	private static final int UPDATE_BATCH_SIZE = 1000;

	private final S3Service s3Service;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;

	@Transactional
	public AdminS3ReconcileResponse reconcile(boolean dryRun) {
		List<String> s3Keys = new ArrayList<>();
		s3Keys.addAll(s3Service.listAllKeys(S3Folder.STORES.getValue() + "/"));
		s3Keys.addAll(s3Service.listAllKeys(S3Folder.MENUS.getValue() + "/"));

		Set<String> dbKeys = new HashSet<>();
		dbKeys.addAll(storeImageFileRepository.findAllAwsKeys());
		dbKeys.addAll(menuImageFileRepository.findAllAwsKeys());

		List<String> orphans = s3Keys.stream()
			.filter(k -> !dbKeys.contains(k))
			.toList();
		List<String> missing = dbKeys.stream()
			.filter(k -> !s3Keys.contains(k))
			.toList();

		int deleted = 0;
		int failedMarked = 0;
		if (!dryRun && !orphans.isEmpty()) {
			deleted = s3Service.deleteObjects(orphans);
			log.warn("[reconcile] deleted {} orphan S3 objects (s3Keys={}, dbKeys={})",
				deleted, s3Keys.size(), dbKeys.size());
		}
		if (!dryRun && !missing.isEmpty()) {
			failedMarked = markMissingAsFailed(missing);
			log.warn("[reconcile] marked {} DB image rows FAILED for missing S3 objects (missing={})",
				failedMarked, missing.size());
		}
		if (dryRun || (orphans.isEmpty() && missing.isEmpty())) {
			log.info("[reconcile] dryRun={} s3Keys={} dbKeys={} orphans={} missing={}",
				dryRun, s3Keys.size(), dbKeys.size(), orphans.size(), missing.size());
		}

		return new AdminS3ReconcileResponse(
			dryRun, s3Keys.size(), dbKeys.size(), orphans.size(), deleted, orphans,
			missing.size(), failedMarked, missing);
	}

	private int markMissingAsFailed(List<String> missingKeys) {
		int updated = 0;
		List<String> storeKeys = missingKeys.stream()
			.filter(key -> key.startsWith(S3Folder.STORES.prefix()))
			.toList();
		List<String> menuKeys = missingKeys.stream()
			.filter(key -> key.startsWith(S3Folder.MENUS.prefix()))
			.toList();
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
}
