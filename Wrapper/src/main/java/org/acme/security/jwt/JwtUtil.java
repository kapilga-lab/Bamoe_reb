package org.acme.security.jwt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.acme.security.jwt.dto.RoleDetailDTO;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

/**
 * Decodes the incoming (unsigned, alg=none) JWT and maps its claims to a
 * {@link UserDetailsJwt}. Mirrors the HRMS SDK behaviour.
 */
@Component
public class JwtUtil {

    private static final String CLAIM_ROLES = "Roles";
    private static final String CLAIM_USER_GROUPS = "userGroups";
    private static final String CLAIM_ROLES_DETAILS = "rolesDetails";
    private static final String CLAIM_PREFERRED_USERNAME = "sub";

    private static final String BEARER_PREFIX = "Bearer ";

    public UserDetailsJwt extractUserDetailsFromToken(String authorization) {
        if (!StringUtils.hasLength(authorization)) {
            throw new IllegalArgumentException("Authorization token is empty or invalid.");
        }

        UserDetailsJwt userDetails = new UserDetailsJwt();
        userDetails.setJwtToken(authorization);

        Claims claims = decodeUnsignedJWT(authorization);

        String tokenUser = (String) claims.get(CLAIM_PREFERRED_USERNAME);
        if (tokenUser == null) {
            throw new IllegalArgumentException("Username (sub) not found in token.");
        }
        userDetails.setUsername(tokenUser);
        userDetails.setRoles(extractRolesFromClaims(claims));
        userDetails.setUserGroups(extractUserGroupsFromClaims(claims));
        userDetails.setRolesDetails(extractRolesDetailsFromClaims(claims));

        return userDetails;
    }

    private static Claims decodeUnsignedJWT(String authorization) {
        if (authorization == null
                || (!authorization.startsWith(BEARER_PREFIX)
                    && !authorization.startsWith(BEARER_PREFIX.toLowerCase()))) {
            throw new IllegalArgumentException("No JWT token found in request headers");
        }
        String authToken = authorization.substring(BEARER_PREFIX.length()).trim();
        String[] splitToken = authToken.split("\\.");
        // Reconstruct the unsigned form: header.payload. (empty signature)
        String unsignedToken = splitToken[0] + "." + splitToken[1] + ".";
        return Jwts.parserBuilder().build().parseClaimsJwt(unsignedToken).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRolesFromClaims(Claims claims) {
        List<String> roles = new ArrayList<>();
        List<String> rolesFromToken = (List<String>) claims.get(CLAIM_ROLES);
        if (rolesFromToken != null) {
            roles.addAll(rolesFromToken);
        }
        return roles;
    }

    @SuppressWarnings("unchecked")
    private List<UserGroupDTO> extractUserGroupsFromClaims(Claims claims) {
        List<UserGroupDTO> userGroups = new ArrayList<>();
        List<LinkedHashMap<String, Object>> fromToken =
                (List<LinkedHashMap<String, Object>>) claims.get(CLAIM_USER_GROUPS);
        if (fromToken != null) {
            for (LinkedHashMap<String, Object> group : fromToken) {
                UserGroupDTO dto = new UserGroupDTO();
                Object groupId = group.get("groupId");
                if (groupId instanceof Number n) {
                    dto.setGroupId(n.longValue());
                }
                dto.setGroupName((String) group.get("groupName"));
                userGroups.add(dto);
            }
        }
        return userGroups;
    }

    @SuppressWarnings("unchecked")
    private List<RoleDetailDTO> extractRolesDetailsFromClaims(Claims claims) {
        List<RoleDetailDTO> rolesDetails = new ArrayList<>();
        List<LinkedHashMap<String, Object>> fromToken =
                (List<LinkedHashMap<String, Object>>) claims.get(CLAIM_ROLES_DETAILS);
        if (fromToken != null) {
            for (LinkedHashMap<String, Object> roleDetail : fromToken) {
                RoleDetailDTO dto = new RoleDetailDTO();
                Object roleId = roleDetail.get("roleId");
                if (roleId instanceof Number n) {
                    dto.setRoleId(n.intValue());
                }
                dto.setRoleName((String) roleDetail.get("roleName"));
                rolesDetails.add(dto);
            }
        }
        return rolesDetails;
    }
}
