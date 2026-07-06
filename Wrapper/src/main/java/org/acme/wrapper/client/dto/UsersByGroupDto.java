package org.acme.wrapper.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * A user record returned by the external userDetails API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsersByGroupDto {

    private String userName;
    private String emailId;
    private String firstName;
    private String lastName;
    private String mobileNo;
    private int isLDAPUser;
    private WorkDetails workDetails;
    private List<String> roles;
    private List<Group> groupDetails;
}
