package com.patobytes.tasks.task;

import com.patobytes.tasks.config.AppProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final AppProperties properties;

    public TaskService(TaskRepository tasks, TaskEventRepository events, AppProperties properties) {
        this.tasks = tasks;
        this.events = events;
        this.properties = properties;
    }

    // ---- reads ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Task> openTasks(UUID ownerId) {
        return tasks.findByOwnerIdAndStatusOrderByPriorityAscCreatedAtAsc(ownerId, TaskStatus.OPEN);
    }

    /**
     * Everything closed or cancelled since local midnight.
     *
     * <p>The boundary is computed in the application timezone and converted to
     * an instant, never by truncating the stored UTC value. Those are different
     * days for three hours out of every twenty-four.
     */
    @Transactional(readOnly = true)
    public List<Task> closedToday(UUID ownerId) {
        Instant startOfDay = LocalDate.now(properties.timezone())
                .atStartOfDay(properties.timezone())
                .toInstant();
        return tasks.findByOwnerIdAndClosedAtGreaterThanEqualOrderByClosedAtDesc(ownerId, startOfDay);
    }

    @Transactional(readOnly = true)
    public Task require(UUID ownerId, UUID id) {
        return tasks.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new TaskNotFound(id));
    }

    @Transactional(readOnly = true)
    public List<TaskEvent> history(UUID ownerId, UUID id) {
        require(ownerId, id);
        return events.findByTaskIdOrderByAtAsc(id);
    }

    /** Children done/total keyed by parent id, for the progress shown on a parent. */
    @Transactional(readOnly = true)
    public Map<UUID, ChildProgress> childProgress(UUID ownerId) {
        Map<UUID, ChildProgress> progress = new HashMap<>();
        for (TaskRepository.ParentProgress row : tasks.parentProgress(ownerId)) {
            progress.put(row.getParentId(), new ChildProgress((int) row.getDone(), (int) row.getTotal()));
        }
        return progress;
    }

    public record ChildProgress(int done, int total) {}

    // ---- writes ---------------------------------------------------------

    @Transactional
    public Task create(UUID ownerId, String title, String description, short priority,
                       TaskContext context, UUID parentId, Instant dueAt, String[] tags) {
        if (parentId != null) {
            requireCanBeParent(ownerId, parentId);
        }
        Task task = new Task(ownerId, title, description, priority, context, parentId, dueAt, tags);
        tasks.save(task);
        events.save(TaskEvent.of(task.getId(), TaskEventType.CREATED));
        return task;
    }

    @Transactional
    public Task edit(UUID ownerId, UUID id, String title, String description, short priority,
                     TaskContext context, UUID parentId, Instant dueAt, String[] tags) {
        Task task = require(ownerId, id);

        short oldPriority = task.getPriority();
        UUID oldParent = task.getParentId();

        task.edit(title, description, priority, context, dueAt, tags);
        events.save(TaskEvent.of(id, TaskEventType.EDITED));

        // Recorded separately from the edit because the analytics ask different
        // questions of them: priority churn, and how long something sat at P0.
        if (oldPriority != priority) {
            events.save(new TaskEvent(id, TaskEventType.PRIORITY_CHANGED,
                    String.valueOf(oldPriority), String.valueOf(priority)));
        }

        if (!Objects.equals(oldParent, parentId)) {
            if (parentId != null) {
                if (parentId.equals(id)) {
                    throw new TaskRuleViolation("A task cannot be its own parent");
                }
                requireCanBeParent(ownerId, parentId);
                if (tasks.countByOwnerIdAndParentId(ownerId, id) > 0) {
                    throw new TaskRuleViolation(
                            "This task has children, so it cannot also become a child. Maximum depth is two.");
                }
            }
            task.reparent(parentId);
            events.save(new TaskEvent(id, TaskEventType.REPARENTED,
                    oldParent == null ? null : oldParent.toString(),
                    parentId == null ? null : parentId.toString()));
        }

        return task;
    }

    @Transactional
    public Task close(UUID ownerId, UUID id) {
        return terminate(ownerId, id, TaskStatus.DONE, TaskEventType.CLOSED);
    }

    @Transactional
    public Task cancel(UUID ownerId, UUID id) {
        return terminate(ownerId, id, TaskStatus.CANCELLED, TaskEventType.CANCELLED);
    }

    @Transactional
    public Task reopen(UUID ownerId, UUID id) {
        Task task = require(ownerId, id);
        if (task.isOpen()) {
            throw new TaskRuleViolation("Task is already open");
        }
        task.reopen();
        events.save(TaskEvent.of(id, TaskEventType.REOPENED));
        return task;
    }

    private Task terminate(UUID ownerId, UUID id, TaskStatus terminal, TaskEventType eventType) {
        Task task = require(ownerId, id);
        if (!task.isOpen()) {
            throw new TaskRuleViolation("Task is already closed");
        }
        // Closing a parent does not cascade. Silently closing someone's open
        // subtasks loses work; refusing makes them look at it.
        if (tasks.existsByOwnerIdAndParentIdAndStatus(ownerId, id, TaskStatus.OPEN)) {
            throw new TaskRuleViolation("This task still has open subtasks. Close or cancel those first.");
        }
        task.close(terminal);
        events.save(TaskEvent.of(id, eventType));
        return task;
    }

    /**
     * Depth is capped at two: a parent may not itself have a parent. Deeper
     * trees make both the UI and "what is actually open right now" harder for
     * no benefit anyone asked for.
     */
    private void requireCanBeParent(UUID ownerId, UUID parentId) {
        Task parent = tasks.findByIdAndOwnerId(parentId, ownerId)
                .orElseThrow(() -> new TaskRuleViolation("Parent task does not exist"));
        if (parent.getParentId() != null) {
            throw new TaskRuleViolation("Cannot nest under a subtask. Maximum depth is two.");
        }
    }
}
