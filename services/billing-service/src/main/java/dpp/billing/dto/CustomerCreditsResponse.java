package dpp.billing.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CustomerCreditsResponse {
    private long totalRemainingVnd;
    private List<CreditWalletItem> credits;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
