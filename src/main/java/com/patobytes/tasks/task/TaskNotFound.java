package com.patobytes.tasks.task;

import java.util.UUID;

/**
 * Also thrown when the task exists but belongs to someone else. Not found and
 * not yours are answered identically on purpose - distinguishing them would
 * confirm the existence of another person's task id.
 */
public class TaskNotFound extends RuntimeException {
    public TaskNotFound(UUID id) {
        super("No such task: " + id);
    }
}
