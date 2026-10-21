package sweetgirl.dataops.proposal.entity;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;

class ChangeProposalTest {

	private ChangeProposal createProposal() {
		return ChangeProposal.builder()
			.storeId(1L)
			.target("store.phone")
			.oldValue("\"02-111-2222\"")
			.newValue("\"02-333-4444\"")
			.source(ProvenanceSource.NAVER)
			.snapshotId(7L)
			.build();
	}

	@Test
	void 생성_시_PENDING_이다() {
		assertThat(createProposal().getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void beginApply_는_점유_시각과_operation_을_남긴다() {
		ChangeProposal proposal = createProposal();
		UUID operationId = UUID.randomUUID();

		proposal.beginApply(operationId);

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.APPLYING);
		assertThat(proposal.getOperationId()).isEqualTo(operationId);
		assertThat(proposal.getApplyStartedAt()).isNotNull();
		// 점유만으로는 심사 완료가 아니다 — 결과 확정에서 남긴다
		assertThat(proposal.getReviewedAt()).isNull();
	}

	@Test
	void completeApply_는_승인_시각을_남긴다() {
		ChangeProposal proposal = createProposal();
		proposal.beginApply(UUID.randomUUID());

		proposal.completeApply(null);

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.APPROVED);
		assertThat(proposal.getReviewedAt()).isNotNull();
	}

	@Test
	void 점유_없이_결과를_확정할_수_없다() {
		ChangeProposal proposal = createProposal();

		assertThatThrownBy(() -> proposal.completeApply(null)).isInstanceOf(ProposalException.class);
		assertThatThrownBy(() -> proposal.failApply("VALUE_DRIFTED", "x")).isInstanceOf(ProposalException.class);
		assertThatThrownBy(() -> proposal.markApplyUnknown("SERVING_ERROR", "x"))
			.isInstanceOf(ProposalException.class);
	}

	@Test
	void 미확정은_되돌리지_않고_UNKNOWN_으로_세운다() {
		ChangeProposal proposal = createProposal();
		proposal.beginApply(UUID.randomUUID());

		proposal.markApplyUnknown("SERVING_ERROR", "connection reset");

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.UNKNOWN);
		assertThat(proposal.getApplyOutcomeReason()).isEqualTo("SERVING_ERROR");
		// 같은 대상을 다시 쓰지 않으려면 PENDING 으로 돌아가서는 안 된다
		assertThatThrownBy(() -> proposal.beginApply(UUID.randomUUID())).isInstanceOf(ProposalException.class);
	}

	@Test
	void reject_는_사유를_남긴다() {
		ChangeProposal proposal = createProposal();

		proposal.reject("오매칭으로 보임");

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.REJECTED);
		assertThat(proposal.getReviewNote()).isEqualTo("오매칭으로 보임");
	}

	@Test
	void PENDING_이_아니면_전이가_거부된다() {
		ChangeProposal proposal = createProposal();
		proposal.beginApply(UUID.randomUUID());
		proposal.completeApply(null);

		assertThatThrownBy(() -> proposal.beginApply(UUID.randomUUID())).isInstanceOf(ProposalException.class);
		assertThatThrownBy(() -> proposal.reject("x")).isInstanceOf(ProposalException.class);
		assertThatThrownBy(proposal::supersede).isInstanceOf(ProposalException.class);
	}
}
