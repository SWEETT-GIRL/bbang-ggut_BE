package sweetgirl.dataops.candidate.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.dataops.candidate.entity.NewStoreCandidate;
import sweetgirl.dataops.candidate.enums.CandidateStatus;

public interface NewStoreCandidateRepository extends JpaRepository<NewStoreCandidate, Long> {

	Optional<NewStoreCandidate> findByMgmtNo(String mgmtNo);

	long countByStatus(CandidateStatus status);
}
