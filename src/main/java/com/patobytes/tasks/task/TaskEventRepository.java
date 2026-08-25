package com.patobytes.tasks.task;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TaskEventRepository extends Repository<TaskEvent, Long> {

    TaskEvent save(TaskEvent event);

    List<TaskEvent> findByTaskIdOrderByAtAsc(UUID taskId);
}
