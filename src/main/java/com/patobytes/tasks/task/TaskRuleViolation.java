package com.patobytes.tasks.task;

/** A request that is well-formed but not allowed by the task rules. Maps to 409. */
public class TaskRuleViolation extends RuntimeException {
    public TaskRuleViolation(String message) {
        super(message);
    }
}
