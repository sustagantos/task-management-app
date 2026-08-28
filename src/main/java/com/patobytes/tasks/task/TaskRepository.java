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

    /** Depth-0 tasks in a given status. The pool a task may be attached to. */
    List<Task> findByOwnerIdAndStatusAndParentIdIsNullOrderByCreatedAtAsc(UUID ownerId, TaskStatus status);

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

    /** Closures in a half-open instant range. Used for the yesterday count on the main page. */
    long countByOwnerIdAndClosedAtGreaterThanEqualAndClosedAtLessThan(
            UUID ownerId, Instant from, Instant to);

    /**
     * History search over closed and cancelled tasks.
     *
     * <p>Open tasks are deliberately excluded: they are all on the main page,
     * which has its own filter. Mixing them in would mean a date range that
     * applies to closed_at for some rows and created_at for others, which is
     * the kind of rule nobody can remember a month later.
     *
     * <p>Every optional filter is cast explicitly. Postgres cannot infer the
     * type of a bare parameter in `:x is null`, and the failure is a runtime
     * "could not determine data type" rather than anything caught at startup.
     *
     * <p>ILIKE rather than full-text search: at a few thousand rows per person
     * a sequential scan is instant, and tsvector would need a column, a trigger
     * and a language choice for a problem nobody has yet.
     */
    @Query(value = """
            select * from task
             where owner_id = :ownerId
               and status <> 'OPEN'
               and closed_at >= :from
               and closed_at < :to
               and (cast(:status as text) is null or status = cast(:status as text))
               and (cast(:context as text) is null or context = cast(:context as text))
               and (cast(:tag as text) is null or cast(:tag as text) = any(tags))
               and (cast(:q as text) is null
                    or title ilike cast(:q as text)
                    or coalesce(description, '') ilike cast(:q as text))
             order by closed_at desc
             limit :limit offset :offset
            """, nativeQuery = true)
    List<Task> history(@Param("ownerId") UUID ownerId,
                       @Param("from") Instant from,
                       @Param("to") Instant to,
                       @Param("status") String status,
                       @Param("context") String context,
                       @Param("tag") String tag,
                       @Param("q") String q,
                       @Param("limit") int limit,
                       @Param("offset") int offset);

    @Query(value = """
            select count(*) from task
             where owner_id = :ownerId
               and status <> 'OPEN'
               and closed_at >= :from
               and closed_at < :to
               and (cast(:status as text) is null or status = cast(:status as text))
               and (cast(:context as text) is null or context = cast(:context as text))
               and (cast(:tag as text) is null or cast(:tag as text) = any(tags))
               and (cast(:q as text) is null
                    or title ilike cast(:q as text)
                    or coalesce(description, '') ilike cast(:q as text))
            """, nativeQuery = true)
    long countHistory(@Param("ownerId") UUID ownerId,
                      @Param("from") Instant from,
                      @Param("to") Instant to,
                      @Param("status") String status,
                      @Param("context") String context,
                      @Param("tag") String tag,
                      @Param("q") String q);
}
