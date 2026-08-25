package com.patobytes.tasks.task;

/**
 * CANCELLED is deliberately distinct from DONE. If abandoned work counted as
 * completion, throughput would be inflated with no way to separate the two
 * afterwards.
 */
public enum TaskStatus {
    OPEN,
    DONE,
    CANCELLED
}
