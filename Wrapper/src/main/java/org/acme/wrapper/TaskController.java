package org.acme.wrapper;

import java.util.List;
import java.util.Map;

import org.acme.wrapper.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wrapper endpoint exposing the current user's tasks. Identity (user + groups) is
 * taken from the JWT via the request-scoped context, so no header parameter is needed.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * @param taskName  optional — keep only tasks with this name (e.g. {@code Maker}).
     * @param processId optional — keep only tasks of this process (e.g. {@code approval}).
     */
    @GetMapping(value = "/my-task", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> myTasks(
            @RequestParam(value = "taskName", required = false) String taskName,
            @RequestParam(value = "processId", required = false) String processId) {
        return taskService.myTasks(taskName, processId);
    }

    /**
     * Every task the current user is or was involved in (current + history, via the
     * Data-Index). Each entry includes {@code workflowName}.
     *
     * @param workflowName optional — keep only tasks of this process (e.g. {@code claimApproval}).
     * @param taskName     optional — keep only tasks with this node name (e.g. {@code Review}).
     * @param state        optional — {@code Ready}, {@code Reserved} or {@code Completed}.
     * @param filter       optional involvement mode — {@code COMPLETED_BY_ME} (tasks I
     *                     completed), {@code NOT_WITH_ME} (earlier with me, no longer
     *                     actionable by me), {@code WITH_ME} (currently actionable by me).
     * @param liveOnly     optional — {@code true} keeps only tasks whose process instance
     *                     is still running (not ended).
     */
    @GetMapping(value = "/involved", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> involvedTasks(
            @RequestParam(value = "workflowName", required = false) String workflowName,
            @RequestParam(value = "taskName", required = false) String taskName,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "liveOnly", required = false, defaultValue = "false") boolean liveOnly) {
        return taskService.involvedTasks(workflowName, taskName, state, filter, liveOnly);
    }

    /**
     * What the current user may do with a task right now.
     *
     * @return {@code {"claim": bool, "release": bool, "complete": bool}}
     */
    @GetMapping(value = "/actions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> taskActions(
            @RequestParam("instanceId") String instanceId,
            @RequestParam("taskId") String taskId) {
        return taskService.taskActions(instanceId, taskId);
    }

    /**
     * Resolve a task id to its workflow coordinates: taskName, instanceId, workflowName
     * (works for completed tasks too).
     */
    @GetMapping(value = "/{taskId}/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> taskInfo(@PathVariable("taskId") String taskId) {
        return taskService.taskInfo(taskId);
    }
}
