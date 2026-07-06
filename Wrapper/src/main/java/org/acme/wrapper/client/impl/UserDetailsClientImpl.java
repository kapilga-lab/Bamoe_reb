package org.acme.wrapper.client.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.acme.wrapper.client.UserDetailsClient;
import org.acme.wrapper.client.dto.UserDetailsResponse;
import org.acme.wrapper.client.dto.UserProfileFilterRequestDto;
import org.acme.wrapper.client.dto.UsersByGroupDto;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the configurable external userDetails API ({@code wrapper.user-details.url}),
 * forwarding the caller's JWT.
 */
@Component
public class UserDetailsClientImpl implements UserDetailsClient {

    private final RestClient client;
    private final String url;

    public UserDetailsClientImpl(
            @Value("${wrapper.user-details.url:http://localhost:9090/api/simulate/userDetails}") String url) {
        this.url = url;
        this.client = RestClient.builder().build();
    }

    @Override
    public List<UsersByGroupDto> fetchUsers(List<String> groupName, List<String> roleNames, String authorization) {
        UserProfileFilterRequestDto body = buildRequest(groupName, roleNames);

        try {
            var request = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);

            if (authorization != null && !authorization.isBlank()) {
                request.header(HttpHeaders.AUTHORIZATION, authorization);
            }

            UserDetailsResponse envelope = request.body(body).retrieve().body(UserDetailsResponse.class);
            if (envelope == null || envelope.getResponse() == null) {
                return List.of();
            }
            return envelope.getResponse();

        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(null, HttpStatus.SERVICE_UNAVAILABLE,
                    "USER_DETAILS_UNREACHABLE",
                    "userDetails service is not reachable at " + url);

        } catch (RestClientResponseException e) {
            throw new WorkflowEngineException(null, HttpStatus.BAD_GATEWAY,
                    "USER_DETAILS_ERROR",
                    "userDetails service returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString());

        } catch (RestClientException e) {
            throw new WorkflowEngineException(null, HttpStatus.BAD_GATEWAY,
                    "USER_DETAILS_ERROR",
                    "Unexpected response from userDetails service: " + e.getMessage());
        }
    }

    private UserProfileFilterRequestDto buildRequest(List<String> groupName, List<String> roleNames) {
        Map<String, List<String>> filterBy = new HashMap<>();
        if (groupName != null && !groupName.isEmpty()) {
            filterBy.put("groupName", new ArrayList<>(groupName));
        }
        if (roleNames != null && !roleNames.isEmpty()) {
            filterBy.put("roleName", new ArrayList<>(roleNames));
        }
        UserProfileFilterRequestDto dto = new UserProfileFilterRequestDto();
        dto.setFilterBy(filterBy);
        return dto;
    }
}
