package com.patobytes.tasks.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately extends {@link Repository}, not {@code JpaRepository}.
 *
 * <p>That base gives you findById and findAll for free, and either would return
 * another person's task to a caller who forgot to check. Here every finder
 * takes ownerId, so an unscoped read is not something a caller can accidentally
 * write - it is something that does not exist.
 */
public interface TaskRepository extends Repository<Task, UUID> {

    Task save(Task task);

    Optional<Task> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Task> findByOwnerIdAndStatusOrderByPriorityAscCreatedAtAsc(UUID ownerId, TaskStatus status);

    /** Closed or cancelled since an instant. Open tasks have a null closedAt, so they cannot match. */
    List<Task> findByOwnerIdAndClosedAtGreaterThanEqualOrderByClosedAtDesc(UUID ownerId, Instant since);

    List<Task> findByOwnerIdAndParentId(UUID ownerId, UUID parentId);

    boolean existsByOwnerIdAndParentIdAndStatus(UUID ownerId, UUID parentId, TaskStatus status);

    long countByOwnerIdAndParentId(UUID ownerId, UUID parentId);

    long countByOwnerIdAndParentIdAndStatusNot(UUID ownerId, UUID parentId, TaskStatus status);

    /**
     * Done/total for every parent this owner has, in one query.
     *
     * <p>Counting per parent would be one query per row on the main page. The
     * closed children are not necessarily in either list being rendered - a
     * child closed last week is in neither - so this cannot be derived on the
     * client either.
     */
    @Query(value = """
            select parent_id as parentId,
                   count(*) as total,
                   count(*) filter (where status <> 'OPEN') as done
              from task
             where owner_id = :ownerId and parent_id is not null
             group by parent_id
            """, nativeQuery = true)
    List<ParentProgress> parentProgress(@Param("ownerId") UUID ownerId);

    interface ParentProgress {
        UUID getParentId();
        long getTotal();
        long getDone();
    }
}
