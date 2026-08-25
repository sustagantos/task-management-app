package com.patobytes.tasks.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task")
public class Task {

    @Id
    private UUID id;

    /**
     * The only thing separating one person's list from another's. Stored as a
     * plain id rather than an association: nothing here needs to navigate to
     * the owner, and a lazy proxy would only invite an accidental fetch.
     */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private short priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskContext context;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] tags = new String[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Task() {
        // for JPA
    }

    public Task(UUID ownerId, String title, String description, short priority,
                TaskContext context, UUID parentId, Instant dueAt, String[] tags) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.context = context;
        this.parentId = parentId;
        this.dueAt = dueAt;
        this.tags = tags == null ? new String[0] : tags;
        this.status = TaskStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Closing and cancelling both set closed_at, which the database requires:
     * a non-OPEN row without a timestamp is rejected by
     * {@code closed_at_matches_status}. Cycle time is measured from this.
     */
    public void close(TaskStatus terminal) {
        if (terminal != TaskStatus.DONE && terminal != TaskStatus.CANCELLED) {
            throw new IllegalArgumentException("Not a terminal status: " + terminal);
        }
        this.status = terminal;
        this.closedAt = Instant.now();
        this.updatedAt = this.closedAt;
    }

    public void reopen() {
        this.status = TaskStatus.OPEN;
        this.closedAt = null;
        this.updatedAt = Instant.now();
    }

    public void edit(String title, String description, short priority,
                     TaskContext context, Instant dueAt, String[] tags) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.context = context;
        this.dueAt = dueAt;
        this.tags = tags == null ? new String[0] : tags;
        this.updatedAt = Instant.now();
    }

    public void reparent(UUID parentId) {
        this.parentId = parentId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getParentId() { return parentId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public short getPriority() { return priority; }
    public TaskContext getContext() { return context; }
    public TaskStatus getStatus() { return status; }
    public String[] getTags() { return tags; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isOpen() { return status == TaskStatus.OPEN; }
}
