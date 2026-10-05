package sweetgirl.croissant.monthlyreport.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft.State;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraftId;

public interface MonthlyReportDraftRepository extends JpaRepository<MonthlyReportDraft, MonthlyReportDraftId> {
	Optional<MonthlyReportDraft> findFirstByRunIdAndStateOrderByIdMemberId(UUID runId, State state);

	long countByRunIdAndState(UUID runId, State state);

	List<MonthlyReportDraft> findByRunIdAndStateIn(UUID runId, Collection<State> states);

	Slice<MonthlyReportDraft> findByRunIdOrderByIdMemberId(UUID runId, Pageable pageable);
}
