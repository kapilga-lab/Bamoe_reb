package org.acme.wrapper.client.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * Request body for the external userDetails API. {@code filterBy} carries the
 * {@code groupName} / {@code roleName} filters.
 */
@Data
public class UserProfileFilterRequestDto {

    private Map<String, List<String>> filterBy;
}
