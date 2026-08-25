package com.patobytes.tasks.task;

/** Append-only history. This is the only source for reopen rate and priority churn. */
public enum TaskEventType {
    CREATED,
    CLOSED,
    REOPENED,
    CANCELLED,
    PRIORITY_CHANGED,
    REPARENTED,
    EDITED
}
