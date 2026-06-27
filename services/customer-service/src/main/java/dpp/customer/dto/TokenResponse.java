package dpp.customer.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TokenResponse {

    private String accessToken;
    private int expiresIn;
    private String tokenType;
    private List<String> roles;

    @JsonIgnore
    private String subject;

    public TokenResponse() {
    }

    public TokenResponse(String accessToken, int expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }

    public TokenResponse(String accessToken, int expiresIn, String tokenType, List<String> roles) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.tokenType = tokenType;
        this.roles = roles;
    }
}
