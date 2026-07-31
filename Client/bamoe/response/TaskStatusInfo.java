package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** Usertask lifecycle status: name = Ready | Reserved | Completed; terminate set when ended. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskStatusInfo {
    private String name;
    private String terminate;
}
