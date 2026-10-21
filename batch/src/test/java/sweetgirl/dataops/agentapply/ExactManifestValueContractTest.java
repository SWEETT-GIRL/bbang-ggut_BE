package sweetgirl.dataops.agentapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.replay.VerifiedReplayAuthority;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import tools.jackson.databind.ObjectMapper;

class ExactManifestValueContractTest {
	@SuppressWarnings("unchecked") private final AgentManifestApplyGate gate=new AgentManifestApplyGate(mock(DataSource.class),
		new ObjectMapper(),mock(ResponseDiagnostics.class),(ObjectProvider<VerifiedReplayAuthority>)mock(ObjectProvider.class));
	private ChangeProposal proposal(String field,String old,String next) {
		return ChangeProposal.builder().storeId(1L).target(field).oldValue(old).newValue(next).source(ProvenanceSource.NAVER).build();
	}
	@Test void implicit_conversions_or_description_truncation_are_not_exact_approved_values() {
		assertThatThrownBy(()->gate.verifySupportedValues(proposal("store.phone","null","123"))).hasMessage("EXACT_VALUE_TYPE_REQUIRED");
		assertThatThrownBy(()->gate.verifySupportedValues(proposal("menu.1.price","100","1.5"))).hasMessage("EXACT_VALUE_TYPE_REQUIRED");
		assertThatThrownBy(()->gate.verifySupportedValues(proposal("menu.1.description","null","\""+"x".repeat(256)+"\""))).hasMessage("EXACT_VALUE_TYPE_REQUIRED");
		gate.verifySupportedValues(proposal("menu.1.price","100","200"));
		gate.verifySupportedValues(proposal("menu.1.description","null","\"정확한 설명\""));
	}
	@Test void fields_with_dynamic_mapping_or_additional_unapproved_writes_are_held() {
		for(String field:List.of("store.category","store.status","store.naver_place_id","menu.1.category","menu.1.removed","future.field"))
			assertThatThrownBy(()->gate.verifySupportedValues(proposal(field,"null","null"))).hasMessage("EXACT_FIELD_ADAPTER_REQUIRED");
	}
	@Test void exact_hours_contract_rejects_duplicates_and_implicit_time_conversions() {
		String row="{\"day\":\"MONDAY\",\"open\":\"09:00\",\"close\":\"18:00\",\"closed\":false}";
		gate.verifySupportedValues(proposal("store.business_hours","[]","["+row+"]"));
		assertThatThrownBy(()->gate.verifySupportedValues(proposal("store.business_hours","[]","["+row+","+row+"]")))
			.hasMessage("EXACT_HOURS_VALUE_REQUIRED");
		assertThatThrownBy(()->gate.verifySupportedValues(proposal("store.business_hours","[]","["+row.replace("09:00","09:00:59")+"]")))
			.hasMessage("EXACT_HOURS_VALUE_REQUIRED");
	}
}
