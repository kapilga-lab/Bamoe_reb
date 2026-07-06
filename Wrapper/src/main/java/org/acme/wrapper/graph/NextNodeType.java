package org.acme.wrapper.graph;

/**
 * Classification of the node reached after completing a task, given the outputs
 * being submitted.
 */
public enum NextNodeType {
    /** The next reachable node is a human (user) task — an assignee is needed. */
    HUMAN,
    /** The flow ends (an end event is reached) — no assignee needed. */
    END,
    /** Could not be determined confidently (unusual gateway/expression/loop). */
    UNKNOWN
}
