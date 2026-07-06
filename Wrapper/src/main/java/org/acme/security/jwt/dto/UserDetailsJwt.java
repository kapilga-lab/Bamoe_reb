package org.acme.security.jwt.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Identity extracted from the incoming JWT for the duration of a request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDetailsJwt {
    private String jwtToken;
    private String username;
    private List<String> roles;
    private List<UserGroupDTO> userGroups;
    private List<RoleDetailDTO> rolesDetails;
}
