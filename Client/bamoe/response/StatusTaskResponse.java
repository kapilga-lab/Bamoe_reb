package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** One task entry inside {@link InstanceStatusResponse}. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StatusTaskResponse {
    private String taskId;
    private String taskName;
    private String state;
    private String actualOwner;
    private List<String> potentialUsers;
    private List<String> potentialGroups;
    private String started;
    private String completed;
}
