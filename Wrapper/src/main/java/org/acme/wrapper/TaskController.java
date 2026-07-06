package org.acme.wrapper;

import java.util.List;
import java.util.Map;

import org.acme.wrapper.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
}
