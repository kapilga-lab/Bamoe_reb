package org.acme.wrapper.service;

import java.util.List;
import java.util.Map;

/**
 * Read operations over the current user's BAMOE user tasks.
 */
public interface TaskService {

    /**
     * Returns the user tasks visible to the current JWT user (by username + group
     * membership), optionally filtered.
     *
     * @param taskName  if non-null, keep only tasks whose {@code taskName} matches.
     * @param processId if non-null, keep only tasks whose {@code processInfo.processId} matches.
     * @return the (filtered) task list exactly as returned by the engine.
     */
    List<Map<String, Object>> myTasks(String taskName, String processId);

    /**
     * Every task the current JWT user is or was involved in — currently assigned/claimable
     * plus historical ones (completed by the user, or where the user was a candidate even
     * if someone else claimed). Backed by the Data-Index (survives task completion).
     *
     * @param workflowName if non-null, keep only tasks of this process.
     * @param taskName     if non-null, keep only tasks with this node name.
     * @param state        if non-null, keep only this lifecycle state
     *                     (e.g. {@code Ready}, {@code Reserved}, {@code Completed}).
     * @param filter       if non-null, an involvement mode: {@code COMPLETED_BY_ME}
     *                     (tasks I completed), {@code NOT_WITH_ME} (earlier with me,
     *                     no longer actionable by me), {@code WITH_ME} (currently
     *                     actionable by me).
     * @param liveOnly     if true, keep only tasks whose process instance is still
     *                     running (not ended/aborted).
     * @return task entries (newest first), each including {@code workflowName}.
     */
    List<Map<String, Object>> involvedTasks(String workflowName, String taskName, String state, String filter,
                                            boolean liveOnly);

    /**
     * What the current JWT user may do with a task right now:
     * <ul>
     *   <li>{@code claim} — task is Ready and the user is a candidate.</li>
     *   <li>{@code release} — task is Reserved by this user.</li>
     *   <li>{@code complete} — task is Ready (candidate) or Reserved by this user.</li>
     * </ul>
     * A task that is invisible to the user (unknown id, or claimed by someone else)
     * yields all-false.
     *
     * @return exactly {@code {"claim": bool, "release": bool, "complete": bool}}.
     */
    Map<String, Boolean> taskActions(String instanceId, String taskId);

    /**
     * Resolve a task id (usertask id or work-item id) to its workflow coordinates.
     * Backed by the Data-Index, so completed tasks resolve too.
     *
     * @return {@code {taskId, taskName, instanceId, workflowName, state, actualOwner}}.
     */
    Map<String, Object> taskInfo(String taskId);
}
