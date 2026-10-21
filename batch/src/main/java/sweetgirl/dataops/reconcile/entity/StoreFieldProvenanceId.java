package sweetgirl.dataops.reconcile.entity;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StoreFieldProvenanceId implements Serializable {

	private Long storeId;
	private String field;
}
