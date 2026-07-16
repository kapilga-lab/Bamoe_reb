package org.acme.wrapper.assignment;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.acme.wrapper.exception.InvalidAssignmentStrategyException;

/**
 * Supported task-assignment strategies. The {@code LAST_ASSIGN_TO_OR_*} family reuses a
 * persisted previous assignee, otherwise falls back to its {@link #base()} strategy.
 */
public enum AssignmentStrategy {

    USER(false, false),
    GROUP(false, false),
    SELF(false, false),
    RANDOM_SELECT(false, true),
    ROUND_ROBIN(false, true),
    TO_ALL_USER(false, true),
    CHOICE(false, true),
    LAST_ASSIGN_TO_OR_USER(true, false),
    LAST_ASSIGN_TO_OR_GROUP(true, false),
    LAST_ASSIGN_TO_OR_SELF(true, false),
    LAST_ASSIGN_TO_OR_RANDOM_SELECT(true, true),
    LAST_ASSIGN_TO_OR_ROUND_ROBIN(true, true),
    LAST_ASSIGN_TO_OR_CHOICE(true, true);

    private final boolean lastAssign;
    private final boolean needsUserDetails;

    AssignmentStrategy(boolean lastAssign, boolean needsUserDetails) {
        this.lastAssign = lastAssign;
        this.needsUserDetails = needsUserDetails;
    }

    /** Whether this strategy first tries the persisted "last assignee". */
    public boolean isLastAssign() {
        return lastAssign;
    }

    /** Whether resolving this strategy requires calling the userDetails API. */
    public boolean needsUserDetails() {
        return needsUserDetails;
    }

    /** The underlying base strategy (LAST_ASSIGN_TO_OR_X -> X; otherwise itself). */
    public AssignmentStrategy base() {
        return switch (this) {
            case LAST_ASSIGN_TO_OR_USER -> USER;
            case LAST_ASSIGN_TO_OR_GROUP -> GROUP;
            case LAST_ASSIGN_TO_OR_SELF -> SELF;
            case LAST_ASSIGN_TO_OR_RANDOM_SELECT -> RANDOM_SELECT;
            case LAST_ASSIGN_TO_OR_ROUND_ROBIN -> ROUND_ROBIN;
            case LAST_ASSIGN_TO_OR_CHOICE -> CHOICE;
            default -> this;
        };
    }

    /** Parse the incoming value, throwing a 400 for anything unsupported. */
    public static AssignmentStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidAssignmentStrategyException(String.valueOf(value), allowed());
        }
        try {
            return AssignmentStrategy.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidAssignmentStrategyException(value, allowed());
        }
    }

    public static String allowed() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
