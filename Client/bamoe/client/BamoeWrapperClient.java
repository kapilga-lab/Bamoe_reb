package bamoe.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.databind.JsonNode;

import bamoe.request.CommentRequest;
import bamoe.request.DecisionConfigRequest;
import bamoe.request.ExecuteTaskRequest;
import bamoe.request.RollbackRequest;
import bamoe.response.AbortResponse;
import bamoe.response.CommentDeleteResponse;
import bamoe.response.CommentPageResponse;
import bamoe.response.CommentResponse;
import bamoe.response.DecisionConfigResponse;
import bamoe.response.DecisionDeleteResponse;
import bamoe.response.InstanceStatusResponse;
import bamoe.response.InvolvedTaskResponse;
import bamoe.response.MyTaskResponse;
import bamoe.response.RollbackResponse;
import bamoe.response.TaskActionsResponse;
import bamoe.response.TaskInfoResponse;

/**
 * Single Feign client for every BAMOE wrapper API.
 *
 * <p><b>Authentication:</b> nothing to pass — the hrms-sdk-lib
 * {@code JwtForwardingRequestInterceptor} (auto-registered when Feign is on the
 * classpath) copies the inbound user's {@code Authorization: Bearer <jwt>} header onto
 * every outbound call.</p>
 *
 * <p>Consumer setup:</p>
 * <pre>
 *   &#64;SpringBootApplication
 *   &#64;EnableFeignClients(clients = BamoeWrapperClient.class)
 *   public class MyApp { ... }
 *
 *   # application.properties
 *   bamoe.client.url=http://localhost:8080
 * </pre>
 */
@FeignClient(name = "bamoe-wrapper", url = "${bamoe.client.url:http://localhost:8080}")
public interface BamoeWrapperClient {

    // ------------------------------------------------------------- /api/workflows

    /**
     * Start a workflow (no instanceId/taskId in the body) or complete/claim/release a
     * task (instanceId + taskId set). The response shape varies: the instance's
     * variables (start 201 / complete 200), or a candidate list for an unresolved
     * CHOICE — hence {@link JsonNode}.
     */
    @PostMapping("/api/workflows/executeTask")
    JsonNode executeTask(@RequestBody ExecuteTaskRequest request);

    /**
     * Array form: start a workflow whose first node is a parallel fork — one item per
     * first human task (each with taskName + its own assignment), plus optional items
     * for start-assigned downstream tasks.
     */
    @PostMapping("/api/workflows/executeTask")
    JsonNode executeTaskBatch(@RequestBody List<ExecuteTaskRequest> items);

    /** Roll a live or ended instance back to a human task (default: last completed). */
    @PostMapping("/api/workflows/rollback")
    RollbackResponse rollback(@RequestBody RollbackRequest request);

    /** Full instance status: ACTIVE/COMPLETED, endedBy (Approved/Rejected/...), tasks. */
    @GetMapping("/api/workflows/{instanceId}/status")
    InstanceStatusResponse instanceStatus(@PathVariable("instanceId") String instanceId);

    /**
     * Abort (delete) a running instance by id — regardless of its stage or who its tasks
     * are assigned to. Idempotent: already-ended instances return {@code aborted=false}.
     */
    @DeleteMapping("/api/workflows/{instanceId}")
    AbortResponse abortInstance(@PathVariable("instanceId") String instanceId);

    // ----------------------------------------------------------------- /api/tasks

    /** The caller's currently actionable tasks (Ready as candidate / Reserved by them). */
    @GetMapping("/api/tasks/my-task")
    List<MyTaskResponse> myTasks(@RequestParam(value = "taskName", required = false) String taskName,
                                 @RequestParam(value = "processId", required = false) String processId);

    /**
     * Every task the caller is or was involved in (data-index backed).
     *
     * @param filter   COMPLETED_BY_ME | NOT_WITH_ME | WITH_ME (null = all)
     * @param liveOnly true → only tasks of still-running instances
     */
    @GetMapping("/api/tasks/involved")
    List<InvolvedTaskResponse> involvedTasks(@RequestParam(value = "workflowName", required = false) String workflowName,
                                             @RequestParam(value = "taskName", required = false) String taskName,
                                             @RequestParam(value = "state", required = false) String state,
                                             @RequestParam(value = "filter", required = false) String filter,
                                             @RequestParam(value = "liveOnly", required = false) Boolean liveOnly);

    /** What the caller may do with a task right now: claim / release / complete. */
    @GetMapping("/api/tasks/actions")
    TaskActionsResponse taskActions(@RequestParam("instanceId") String instanceId,
                                    @RequestParam("taskId") String taskId);

    /** Resolve a task id to its taskName + instanceId + workflowName (works when completed too). */
    @GetMapping("/api/tasks/{taskId}/info")
    TaskInfoResponse taskInfo(@PathVariable("taskId") String taskId);

    // -------------------------------------------------------------- /api/comments

    /** Add a comment to an active task (allowed for whoever can act on it). */
    @PostMapping("/api/comments")
    CommentResponse addComment(@RequestBody CommentRequest request);

    /** Update own comment (task must still be active). */
    @PutMapping("/api/comments/{id}")
    CommentResponse updateComment(@PathVariable("id") long id, @RequestBody CommentRequest request);

    /** Delete own comment (task must still be active). */
    @DeleteMapping("/api/comments/{id}")
    CommentDeleteResponse deleteComment(@PathVariable("id") long id);

    /** Paged comments of an instance, optionally filtered by taskName / commentBy. */
    @GetMapping("/api/comments")
    CommentPageResponse getComments(@RequestParam("instanceId") String instanceId,
                                    @RequestParam(value = "taskName", required = false) String taskName,
                                    @RequestParam(value = "commentBy", required = false) String commentBy,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    @RequestParam(value = "size", required = false) Integer size);

    // ------------------------------------------------------------- /api/decisions

    /** Configure a task's (or START stage's) decision variable + allowed values. */
    @PostMapping("/api/decisions")
    DecisionConfigResponse saveDecision(@RequestBody DecisionConfigRequest request);

    /** One task's decision config (404 → FeignException when not configured). */
    @GetMapping("/api/decisions")
    DecisionConfigResponse getDecision(@RequestParam("workflowName") String workflowName,
                                       @RequestParam("taskName") String taskName);

    /** All decision configs of a workflow. */
    @GetMapping("/api/decisions")
    List<DecisionConfigResponse> getDecisions(@RequestParam("workflowName") String workflowName);

    /** Remove a decision config. */
    @DeleteMapping("/api/decisions")
    DecisionDeleteResponse deleteDecision(@RequestParam("workflowName") String workflowName,
                                          @RequestParam("taskName") String taskName);
}
