package org.acme.wrapper.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Envelope returned by the simulate userDetails API; the actual users are under
 * {@code response}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserDetailsResponse {

    private String name;
    private boolean valid;
    private String message;
    private List<UsersByGroupDto> response;
}
