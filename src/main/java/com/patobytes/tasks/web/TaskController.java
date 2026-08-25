package com.patobytes.tasks.web;

import com.patobytes.tasks.config.AppProperties;
import com.patobytes.tasks.task.Task;
import com.patobytes.tasks.task.TaskContext;
import com.patobytes.tasks.task.TaskEvent;
import com.patobytes.tasks.task.TaskService;
import com.patobytes.tasks.task.TaskService.ChildProgress;
import com.patobytes.tasks.task.TaskStatus;
import com.patobytes.tasks.user.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;
    private final CurrentUserService currentUser;
    private final AppProperties properties;

    public TaskController(TaskService service, CurrentUserService currentUser, AppProperties properties) {
        this.service = service;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    // ---- payloads -------------------------------------------------------

    public record TaskRequest(
            @NotBlank @Size(max = 500) String title,
            String description,
            @Min(0) @Max(3) Short priority,
            @NotNull TaskContext context,
            UUID parentId,
            Instant dueAt,
            List<String> tags) {

        short priorityOrDefault() {
            return priority == null ? (short) 2 : priority;
        }

        String[] tagsOrEmpty() {
            return tags == null ? new String[0] : tags.toArray(String[]::new);
        }
    }

    public record TaskView(
            UUID id,
            UUID parentId,
            String title,
            String description,
            short priority,
            TaskContext context,
            TaskStatus status,
            List<String> tags,
            Instant createdAt,
            Instant closedAt,
            Instant dueAt,
            int childrenDone,
            int childrenTotal) {

        static TaskView of(Task task, Map<UUID, ChildProgress> progress) {
            ChildProgress p = progress.get(task.getId());
            return new TaskView(
                    task.getId(), task.getParentId(), task.getTitle(), task.getDescription(),
                    task.getPriority(), task.getContext(), task.getStatus(),
                    List.of(task.getTags()), task.getCreatedAt(), task.getClosedAt(), task.getDueAt(),
                    p == null ? 0 : p.done(), p == null ? 0 : p.total());
        }
    }

    /**
     * The main page in one round trip.
     *
     * <p>{@code timezone} travels with the payload so the browser formats and
     * schedules its midnight rollover against the same day boundary the server
     * used to decide what "closed today" means. Letting the client use its own
     * timezone is how the two quietly disagree for travellers.
     */
    public record MainPage(List<TaskView> open, List<TaskView> closedToday, String timezone) {}

    public record EventView(Instant at, String type, String fromValue, String toValue) {
        static EventView of(TaskEvent e) {
            return new EventView(e.getAt(), e.getType().name(), e.getFromValue(), e.getToValue());
        }
    }

    // ---- endpoints ------------------------------------------------------

    @GetMapping
    public MainPage mainPage() {
        UUID owner = currentUser.require().getId();
        Map<UUID, ChildProgress> progress = service.childProgress(owner);
        return new MainPage(
                service.openTasks(owner).stream().map(t -> TaskView.of(t, progress)).toList(),
                service.closedToday(owner).stream().map(t -> TaskView.of(t, progress)).toList(),
                properties.timezone().getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@Valid @RequestBody TaskRequest request) {
        UUID owner = currentUser.require().getId();
        Task task = service.create(owner, request.title(), request.description(),
                request.priorityOrDefault(), request.context(), request.parentId(),
                request.dueAt(), request.tagsOrEmpty());
        return TaskView.of(task, Map.of());
    }

    @PatchMapping("/{id}")
    public TaskView edit(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        UUID owner = currentUser.require().getId();
        Task task = service.edit(owner, id, request.title(), request.description(),
                request.priorityOrDefault(), request.context(), request.parentId(),
                request.dueAt(), request.tagsOrEmpty());
        return TaskView.of(task, service.childProgress(owner));
    }

    @PostMapping("/{id}/close")
    public TaskView close(@PathVariable UUID id) {
        UUID owner = currentUser.require().getId();
        return TaskView.of(service.close(owner, id), service.childProgress(owner));
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable UUID id) {
        UUID owner = currentUser.require().getId();
        return TaskView.of(service.cancel(owner, id), service.childProgress(owner));
    }

    @PostMapping("/{id}/reopen")
    public TaskView reopen(@PathVariable UUID id) {
        UUID owner = currentUser.require().getId();
        return TaskView.of(service.reopen(owner, id), service.childProgress(owner));
    }

    @GetMapping("/{id}/history")
    public List<EventView> history(@PathVariable UUID id) {
        UUID owner = currentUser.require().getId();
        return service.history(owner, id).stream().map(EventView::of).toList();
    }
}
