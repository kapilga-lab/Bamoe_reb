package org.acme.wrapper.client;

import java.util.List;

import org.acme.wrapper.client.dto.UsersByGroupDto;

/**
 * Client for the external userDetails API that returns candidate users filtered by
 * group and/or role.
 */
public interface UserDetailsClient {

    /**
     * @param groupName     group filter (may be null/empty).
     * @param roleNames     role filter (may be null/empty).
     * @param authorization the incoming {@code Authorization} header to forward (may be null).
     * @return matching users (never null).
     */
    List<UsersByGroupDto> fetchUsers(List<String> groupName, List<String> roleNames, String authorization);
}
