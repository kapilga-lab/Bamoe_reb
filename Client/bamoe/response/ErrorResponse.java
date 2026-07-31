package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Error body returned by the wrapper on 4xx/5xx, e.g.
 * {@code {"error":"INVALID_DECISION","workflow":"approval","message":"..."}}.
 * Decode it in a Feign {@code ErrorDecoder} or from {@code FeignException#contentUTF8()}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorResponse {
    private String error;
    private String workflow;
    private String message;
}
