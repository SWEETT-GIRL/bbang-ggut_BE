package sweetgirl.dataops.proposal.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.dataops.DataopsApplication;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.enums.ProposalTargetFamily;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import jakarta.persistence.EntityManager;

/**
 * 재판정 범위 정리의 경계 (v3 P1-2 · AC-09) — 전역 PENDING 삭제를 대체한 쿼리가 범위 밖을 건드리지 않는지 본다.
 * 이전 구현은 네이버 스냅샷 재판정 한 번이 공공데이터 폐업 제안·매칭 제안·지역 제안까지 함께 날렸다.
 */
@SpringBootTest(properties = "spring.config.name=" + DataopsApplication.CONFIG_NAME)
@Transactional
class ChangeProposalRepositoryTest {

	/** ReconcileService.JUDGED_FAMILIES 와 같은 범위 */
	private static final List<ProposalTargetFamily> JUDGED = List.of(
		ProposalTargetFamily.STORE_NAME, ProposalTargetFamily.STORE_PHONE, ProposalTargetFamily.STORE_INSTAGRAM,
		ProposalTargetFamily.STORE_ADDRESS, ProposalTargetFamily.BUSINESS_HOURS,
		ProposalTargetFamily.MENU_CONTENT, ProposalTargetFamily.MENU_REMOVAL, ProposalTargetFamily.MENU_CATEGORY);

	@Autowired
	private ChangeProposalRepository repository;
	@Autowired
	private StoreSyncStateRepository storeSyncStateRepository;
	@Autowired
	private EntityManager entityManager;

	private ChangeProposal reconcileSurvivor;
	private ChangeProposal reconcileStale;
	private ChangeProposal localdataClosure;
	private ChangeProposal naverMatching;
	private ChangeProposal kakaoRegion;
	private ChangeProposal otherStore;
	private ChangeProposal legacyUnknown;
	private ChangeProposal applying;

	@BeforeEach
	void seed() {
		repository.deleteAll();
		reconcileSurvivor = save(1L, "store.name", ProvenanceSource.NAVER, ProposalProducer.NAVER_SNAPSHOT_RECONCILE);
		reconcileStale = save(1L, "menu.77.removed", ProvenanceSource.NAVER, ProposalProducer.NAVER_SNAPSHOT_RECONCILE);
		localdataClosure = save(1L, "store.status", ProvenanceSource.LOCALDATA, ProposalProducer.LOCALDATA_STATUS);
		naverMatching = save(1L, "store.naver_place_id", ProvenanceSource.NAVER, ProposalProducer.NAVER_MATCHING);
		kakaoRegion = save(1L, "store.region", ProvenanceSource.KAKAO, ProposalProducer.REGION_RECOMPUTE);
		otherStore = save(2L, "menu.88.removed", ProvenanceSource.NAVER, ProposalProducer.NAVER_SNAPSHOT_RECONCILE);
		legacyUnknown = save(1L, "store.phone", ProvenanceSource.NAVER, ProposalProducer.LEGACY_UNKNOWN);
		applying = save(1L, "store.instagram_handle", ProvenanceSource.NAVER,
			ProposalProducer.NAVER_SNAPSHOT_RECONCILE);
		applying.beginApply(UUID.randomUUID());
		repository.flush();
	}

	private ChangeProposal save(long storeId, String target, ProvenanceSource source, ProposalProducer producer) {
		return repository.save(ChangeProposal.builder()
			.storeId(storeId).target(target).newValue("\"값\"").source(source)
			.producer(producer).policyVersion("test").reconcileRunId(UUID.randomUUID())
			.build());
	}

	private ProposalStatus statusOf(ChangeProposal proposal) {
		entityManager.clear();
		return repository.findById(proposal.getId()).orElseThrow().getStatus();
	}

	@Test
	void 범위_정리는_같은_가게_같은_producer_의_이번_판정에_안_나온_PENDING_만_넘긴다() {
		int expired = repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(reconcileSurvivor.getId()), 1, "재판정에서 더 이상 나오지 않음");
		repository.flush();

		assertThat(expired).isEqualTo(1);
		assertThat(statusOf(reconcileStale)).isEqualTo(ProposalStatus.SUPERSEDED);
		assertThat(statusOf(reconcileSurvivor)).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void 범위_정리는_다른_producer_의_제안을_건드리지_않는다() {
		repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(reconcileSurvivor.getId()), 1, "정리");
		repository.flush();

		// 공공데이터 폐업·네이버 매칭·카카오 지역 — 전역 삭제 시절 함께 사라졌던 것들
		assertThat(statusOf(localdataClosure)).isEqualTo(ProposalStatus.PENDING);
		assertThat(statusOf(naverMatching)).isEqualTo(ProposalStatus.PENDING);
		assertThat(statusOf(kakaoRegion)).isEqualTo(ProposalStatus.PENDING);
		// 만든 주체를 모르는 과거 행도 이 범위의 대상이 아니다
		assertThat(statusOf(legacyUnknown)).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void 범위_정리는_다른_가게와_반영_중인_제안을_건드리지_않는다() {
		repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(reconcileSurvivor.getId()), 1, "정리");
		repository.flush();

		assertThat(statusOf(otherStore)).isEqualTo(ProposalStatus.PENDING);
		// APPLYING 은 운영 반영이 진행 중이다 — 세대 정리로 상태를 바꾸면 결과 확정이 터진다
		assertThat(statusOf(applying)).isEqualTo(ProposalStatus.APPLYING);
	}

	@Test
	void 살아남은_제안이_없으면_그_가게_그_producer_의_PENDING_을_모두_넘긴다() {
		// 네이버 값이 운영과 같아져 diff 가 하나도 안 나온 경우
		int expired = repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(0L), 0, "정리");
		repository.flush();

		assertThat(expired).isEqualTo(2);
		assertThat(statusOf(reconcileSurvivor)).isEqualTo(ProposalStatus.SUPERSEDED);
		assertThat(statusOf(reconcileStale)).isEqualTo(ProposalStatus.SUPERSEDED);
		assertThat(statusOf(localdataClosure)).isEqualTo(ProposalStatus.PENDING);
		assertThat(statusOf(applying)).isEqualTo(ProposalStatus.APPLYING);
	}

	@Test
	void 판정이_보지_않은_묶음은_같은_producer_라도_남는다() {
		ChangeProposal outOfFamily = save(1L, "store.category", ProvenanceSource.NAVER,
			ProposalProducer.NAVER_SNAPSHOT_RECONCILE);
		repository.flush();

		repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(0L), 0, "정리");
		repository.flush();

		assertThat(statusOf(outOfFamily)).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void target_family_는_target_에서_결정된다() {
		assertThat(repository.findById(reconcileStale.getId()).orElseThrow().getTargetFamily())
			.isEqualTo(ProposalTargetFamily.MENU_REMOVAL);
		assertThat(repository.findById(kakaoRegion.getId()).orElseThrow().getTargetFamily())
			.isEqualTo(ProposalTargetFamily.STORE_REGION);
	}

	@Test
	void 범위_정리는_같은_트랜잭션의_다른_엔티티_변경을_버리지_않는다() {
		// 2026-09-26 표본 수집에서 드러난 결함 — 재판정 끝의 범위 정리가 clearAutomatically 로 영속성 컨텍스트를
		// 비워, 같은 트랜잭션에서 먼저 한 sync_state 갱신(visit·해시·last_success·next_due_at)이 flush 전에 버려졌다.
		// 스냅샷 INSERT 는 이미 나갔으므로 "스냅샷은 있는데 방문 기록이 없는" 상태가 남았다(표본 100곳 중 18곳).
		StoreSyncState saved = storeSyncStateRepository.saveAndFlush(StoreSyncState.builder()
			.storeId(900L).source(SyncSource.NAVER).externalId("nv-900").tier(SyncTier.T1).build());
		entityManager.clear();

		StoreSyncState managed = storeSyncStateRepository.findById(saved.getId()).orElseThrow();
		managed.recordSuccess(true, "hash-after-visit");   // 아직 flush 전인 변경
		repository.expireOutOfGeneration(1L, ProposalProducer.NAVER_SNAPSHOT_RECONCILE, JUDGED,
			List.of(reconcileSurvivor.getId()), 1, "정리");
		entityManager.flush();
		entityManager.clear();

		StoreSyncState reread = storeSyncStateRepository.findById(saved.getId()).orElseThrow();
		assertThat(reread.getVisitCount()).isEqualTo(1);
		assertThat(reread.getContentHash()).isEqualTo("hash-after-visit");
	}
}
