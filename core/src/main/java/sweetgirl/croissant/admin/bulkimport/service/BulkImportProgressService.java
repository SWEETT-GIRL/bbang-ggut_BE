package sweetgirl.croissant.admin.bulkimport.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.repository.BulkImportProgressRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulkImportProgressService {

	private final BulkImportProgressRepository repository;

	@Transactional
	public BulkImportProgress findOrCreate(String region1st, String region2nd, String region3rd) {
		return repository.findByRegion1stAndRegion2ndAndRegion3rd(region1st, region2nd, region3rd)
			.orElseGet(() -> repository.save(BulkImportProgress.of(region1st, region2nd, region3rd)));
	}

	public List<BulkImportProgress> getPendingOrFailed() {
		return repository.findByStatusIn(List.of(BulkImportStatus.PENDING, BulkImportStatus.FAILED));
	}

	public List<BulkImportProgress> getFailed() {
		return repository.findByStatusOrderByLastAttemptedAtDesc(BulkImportStatus.FAILED);
	}

	@Transactional
	public int recoverStaleInProgress() {
		return repository.bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING);
	}

	@Transactional
	public void markInProgress(BulkImportProgress progress) {
		progress.markInProgress();
		repository.save(progress);
	}

	@Transactional
	public void markCompleted(BulkImportProgress progress, int foundCount, int insertedCount) {
		progress.markCompleted(foundCount, insertedCount);
		repository.save(progress);
	}

	@Transactional
	public void markFailed(BulkImportProgress progress, String errorMessage) {
		progress.markFailed(errorMessage);
		repository.save(progress);
	}

	public long countByStatus(BulkImportStatus status) {
		return repository.countByStatus(status);
	}

	public long countTotal() {
		return repository.count();
	}

	public long sumInsertedCount() {
		return repository.findAll().stream().mapToLong(BulkImportProgress::getInsertedCount).sum();
	}
}
