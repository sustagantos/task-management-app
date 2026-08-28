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

    /** How many closed yesterday, for the link off the main page into history. */
    @Transactional(readOnly = true)
    public long closedYesterdayCount(UUID ownerId) {
        LocalDate today = LocalDate.now(properties.timezone());
        Instant startOfToday = today.atStartOfDay(properties.timezone()).toInstant();
        Instant startOfYesterday = today.minusDays(1).atStartOfDay(properties.timezone()).toInstant();
        return tasks.countByOwnerIdAndClosedAtGreaterThanEqualAndClosedAtLessThan(
                ownerId, startOfYesterday, startOfToday);
    }

    /**
     * History over closed and cancelled tasks.
     *
     * <p>Callers pass local dates, because that is what a person picks. The
     * conversion to instants happens here against the application timezone, so
     * "the 3rd" means the 3rd in Sao Paulo and not a UTC day that starts at
     * 21:00 the evening before.
     *
     * <p>{@code to} is inclusive of the whole day: the range becomes
     * [from 00:00, to+1 day 00:00).
     */
    @Transactional(readOnly = true)
    public HistoryPage history(UUID ownerId, LocalDate from, LocalDate to,
                               TaskStatus status, TaskContext context,
                               String tag, String query, int limit, int offset) {
        Instant fromInstant = from.atStartOfDay(properties.timezone()).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(properties.timezone()).toInstant();

        String statusFilter = status == null ? null : status.name();
        String contextFilter = context == null ? null : context.name();
        String tagFilter = (tag == null || tag.isBlank()) ? null : tag.trim();

        // Wildcards belong here, not in the query string. Escaping the LIKE
        // metacharacters first stops a search for "50%" matching everything.
        String like = null;
        if (query != null && !query.isBlank()) {
            String escaped = query.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            like = "%" + escaped + "%";
        }

        List<Task> items = tasks.history(ownerId, fromInstant, toInstant,
                statusFilter, contextFilter, tagFilter, like, limit, offset);
        long total = tasks.countHistory(ownerId, fromInstant, toInstant,
                statusFilter, contextFilter, tagFilter, like);

        return new HistoryPage(items, total, limit, offset);
    }

    public record HistoryPage(List<Task> items, long total, int limit, int offset) {}

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

    public record TaskDetail(Task task, Task parent, List<Task> children, List<TaskEvent> events) {}

    /** Everything the detail panel shows, in one round trip. */
    @Transactional(readOnly = true)
    public TaskDetail detail(UUID ownerId, UUID id) {
        Task task = require(ownerId, id);
        Task parent = task.getParentId() == null
                ? null
                : tasks.findByIdAndOwnerId(task.getParentId(), ownerId).orElse(null);
        return new TaskDetail(
                task,
                parent,
                tasks.findByOwnerIdAndParentId(ownerId, id),
                events.findByTaskIdOrderByAtAsc(id));
    }

    /**
     * Open, top-level tasks this one could be attached to.
     *
     * <p>Only depth-0 tasks qualify, because the depth cap of two means a
     * subtask cannot itself be a parent. Closed tasks are excluded: attaching
     * live work to something already finished reads as a mistake.
     */
    @Transactional(readOnly = true)
    public List<Task> parentCandidates(UUID ownerId, UUID excluding) {
        return tasks.findByOwnerIdAndStatusAndParentIdIsNullOrderByCreatedAtAsc(ownerId, TaskStatus.OPEN)
                .stream()
                .filter(t -> !t.getId().equals(excluding))
                .toList();
    }

    /**
     * Attach to a parent, move between parents, or detach with a null.
     *
     * <p>Separate from {@link #edit} so the detail panel can change one
     * relationship without resending every field - a PATCH that carries the
     * whole task will happily overwrite a description someone edited in another
     * tab.
     */
    @Transactional
    public Task reparent(UUID ownerId, UUID id, UUID newParentId) {
        Task task = require(ownerId, id);
        UUID oldParentId = task.getParentId();
        if (Objects.equals(oldParentId, newParentId)) {
            return task;
        }

        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new TaskRuleViolation("A task cannot be its own parent");
            }
            requireCanBeParent(ownerId, newParentId);
            if (tasks.countByOwnerIdAndParentId(ownerId, id) > 0) {
                throw new TaskRuleViolation(
                        "This task already has subtasks, so it cannot become one. Maximum depth is two.");
            }
        }

        task.reparent(newParentId);
        events.save(new TaskEvent(id, TaskEventType.REPARENTED,
                oldParentId == null ? null : oldParentId.toString(),
                newParentId == null ? null : newParentId.toString()));
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
        // Closing is independent of the hierarchy in both directions: it does not
        // cascade to children, and open children do not block a parent.
        //
        // This originally refused to close a parent that still had open
        // subtasks. That was wrong in practice - a parent is often finished
        // while a low-priority subtask stays open indefinitely, and the block
        // just forced busywork. The link is informational, not structural.
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
