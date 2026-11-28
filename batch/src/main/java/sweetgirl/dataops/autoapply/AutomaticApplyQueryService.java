package sweetgirl.dataops.autoapply;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyQueryService {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final ChangeProposalRepository proposals;
	private final StoreFieldProvenanceRepository provenance;

	public AutomaticApplyQueryService(DataSource dataSource, ObjectMapper mapper,
			ChangeProposalRepository proposals, StoreFieldProvenanceRepository provenance) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.mapper = mapper;
		this.proposals = proposals;
		this.provenance = provenance;
	}

	public record CaptureResponseDto(SnapshotSubmitRequest request, boolean verified, String status, JsonNode receipt) {}
	public record PlanResponseDto(String version, List<AutomaticCandidate> candidates, JsonNode results) {}
	public record ProposalStateResponseDto(long id, ProposalStatus status, String reason) {
		static ProposalStateResponseDto from(ChangeProposal proposal) {
			return new ProposalStateResponseDto(proposal.getId(), proposal.getStatus(),
				Objects.toString(proposal.getApplyOutcomeReason(), proposal.getStatus().name()));
		}
	}

	public ProposalStateResponseDto findProposal(long id) {
		return proposals.findById(id).map(ProposalStateResponseDto::from)
			.orElseThrow(() -> new AutomaticApplyException.Hold(AutomaticApplyException.Reason.PROPOSAL_MISSING));
	}
	public Map<String, ProvenanceSource> findSources(long storeId) {
		Map<String, ProvenanceSource> result = new LinkedHashMap<>();
		provenance.findByStoreId(storeId).forEach(value -> result.put(value.getField(), value.getSource()));
		return result;
	}
	public Optional<PlanResponseDto> findPlan(String captureId) {
		return jdbc.query("SELECT policy_version, candidates::text, results::text FROM response_auto_apply WHERE capture_id = ?",
			(row, index) -> new PlanResponseDto(row.getString(1), Arrays.asList(mapper.readValue(row.getString(2), AutomaticCandidate[].class)),
				mapper.readTree(row.getString(3))), captureId).stream().findFirst();
	}
	public CaptureResponseDto findCapture(String captureId) {
		return jdbc.query("SELECT * FROM response_capture WHERE capture_id = ?", (row, index) -> new CaptureResponseDto(
			new SnapshotSubmitRequest(row.getLong("store_id"), SyncSource.valueOf(row.getString("source")),
				mapper.readTree(row.getString("payload")), null, captureId, row.getString("external_id"),
				row.getObject("observed_at", OffsetDateTime.class), row.getObject("attempt_seq", Long.class)),
			row.getBoolean("target_verified"), row.getString("status"),
			mapper.readTree(Objects.toString(row.getString("processed_response"), "null"))), captureId)
			.stream().findFirst().orElseThrow(() -> new AutomaticApplyException.Hold(AutomaticApplyException.Reason.CAPTURE_MISSING));
	}
	public boolean isLatestCapture(String captureId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT NOT EXISTS (SELECT 1 FROM response_capture newer JOIN response_capture current ON current.capture_id = ?
			WHERE newer.store_id = current.store_id AND newer.source = current.source AND newer.capture_id <> current.capture_id
			AND (newer.received_at >= current.received_at OR newer.observed_at > current.observed_at))
			""", Boolean.class, captureId));
	}
}
