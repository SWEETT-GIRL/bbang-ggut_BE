package sweetgirl.dataops.reconcile.policy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.enums.ReconcileMode;

class MergePolicyTest {

	private MergePolicy policyWithMode(ReconcileMode mode) {
		MergePolicy mergePolicy = new MergePolicy();
		ReflectionTestUtils.setField(mergePolicy, "mode", mode);
		return mergePolicy;
	}

	@Test
	void PROPOSAL_ONLY_모드는_안전_필드도_전부_proposal_이다() {
		MergePolicy mergePolicy = policyWithMode(ReconcileMode.PROPOSAL_ONLY);

		assertThat(mergePolicy.decide("store.phone", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
		assertThat(mergePolicy.decide("menu.add.소금빵", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
	}

	@Test
	void MATRIX_모드는_안전_필드만_AUTO_다() {
		MergePolicy mergePolicy = policyWithMode(ReconcileMode.MATRIX);

		assertThat(mergePolicy.decide("store.phone", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.AUTO);
		assertThat(mergePolicy.decide("menu.add.소금빵", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.AUTO);
		assertThat(mergePolicy.decide("menu.77.price", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.AUTO);
		assertThat(mergePolicy.decide("store.name", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
		assertThat(mergePolicy.decide("menu.77.removed", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
		// 정책 재판정 재분류 — 분류기 오판(doubt 밖)은 사람이 걸러야 해서 MATRIX 에서도 자동 반영하지 않는다
		assertThat(mergePolicy.decide("menu.77.category", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
		assertThat(mergePolicy.decide("store.status", ProvenanceSource.NAVER, null))
			.isEqualTo(MergeDecision.PROPOSAL);
	}

	@Test
	void 상위_출처가_기록된_필드는_MATRIX_모드에서도_proposal_로_강등된다() {
		MergePolicy mergePolicy = policyWithMode(ReconcileMode.MATRIX);

		// USER 가 기록된 필드에 NAVER diff — 사용자 기여 보호 (리서치 2.5)
		assertThat(mergePolicy.decide("menu.add.소금빵", ProvenanceSource.NAVER, ProvenanceSource.USER))
			.isEqualTo(MergeDecision.PROPOSAL);
		assertThat(mergePolicy.decide("store.phone", ProvenanceSource.NAVER, ProvenanceSource.ADMIN))
			.isEqualTo(MergeDecision.PROPOSAL);
		// 동급 출처는 강등 없음
		assertThat(mergePolicy.decide("store.phone", ProvenanceSource.NAVER, ProvenanceSource.NAVER))
			.isEqualTo(MergeDecision.AUTO);
	}
}
