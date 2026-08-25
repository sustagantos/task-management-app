package com.patobytes.tasks.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only row per state change.
 *
 * <p>Never updated and never deleted except by cascade when its task goes. The
 * task row carries current state so the main page is one cheap query; this
 * carries the history that reopen rate, priority churn and time-at-priority are
 * computed from. None of those can be reconstructed if a write is skipped, so
 * every mutation writes one of these in the same transaction.
 */
@Entity
@Table(name = "task_event")
public class TaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TaskEventType type;

    @Column(name = "from_value", updatable = false)
    private String fromValue;

    @Column(name = "to_value", updatable = false)
    private String toValue;

    protected TaskEvent() {
        // for JPA
    }

    public TaskEvent(UUID taskId, TaskEventType type, String fromValue, String toValue) {
        this.taskId = taskId;
        this.type = type;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.at = Instant.now();
    }

    public static TaskEvent of(UUID taskId, TaskEventType type) {
        return new TaskEvent(taskId, type, null, null);
    }

    public Long getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public Instant getAt() { return at; }
    public TaskEventType getType() { return type; }
    public String getFromValue() { return fromValue; }
    public String getToValue() { return toValue; }
}
