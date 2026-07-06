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
}
