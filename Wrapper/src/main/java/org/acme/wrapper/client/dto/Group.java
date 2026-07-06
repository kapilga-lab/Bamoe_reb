package org.acme.wrapper.client.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Group {
    private String groupName;
    private List<String> roles;
}
