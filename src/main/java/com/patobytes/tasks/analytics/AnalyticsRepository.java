package com.patobytes.tasks.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tier 1 analytics. Native SQL by design.
 *
 * <p>JPQL cannot express window functions, {@code percentile_cont} or
 * {@code generate_series}, and fighting it would be the largest waste of effort
 * in this project. These are read-only aggregates over a few thousand rows per
 * person, so nothing is materialised or cached.
 *
 * <p>Every query is scoped by owner_id. None of the thirteen reports aggregate
 * across users.
 *
 * <p>Day and week boundaries are computed with {@code at time zone} against the
 * application timezone, never by truncating the stored UTC value - those are
 * different days for three hours out of every twenty-four.
 */
@Repository
public class AnalyticsRepository {

    private final JdbcClient db;

    public AnalyticsRepository(JdbcClient db) {
        this.db = db;
    }

    // ---- 1. Throughput --------------------------------------------------

    public record ThroughputPoint(LocalDate day, int workDone, int personalDone, int cancelled,
                                  double trailing7, double trailing28) {}

    /**
     * Closures per local day, split work/personal, with trailing averages.
     *
     * <p>The spine starts 27 days before the requested window so the trailing-28
     * average of the first visible day is computed over a full 28 days rather
     * than over however many rows happen to precede it. The trim happens in an
     * outer query because WHERE is evaluated before window functions - filtering
     * inline would silently produce the partial-window numbers this avoids.
     */
    public List<ThroughputPoint> throughput(UUID ownerId, String tz, int days) {
        return db.sql("""
                with bounds as (
                    select (now() at time zone cast(:tz as text))::date - (cast(:days as int) - 1) as first_day,
                           (now() at time zone cast(:tz as text))::date as last_day,
                           cast(:tz as text) as tz
                ),
                spine as (
                    select generate_series(b.first_day - 27, b.last_day, interval '1 day')::date as day
                      from bounds b
                ),
                closed as (
                    select (t.closed_at at time zone b.tz)::date as day,
                           count(*) filter (where t.status = 'DONE' and t.context = 'WORK') as work_done,
                           count(*) filter (where t.status = 'DONE' and t.context = 'PERSONAL') as personal_done,
                           count(*) filter (where t.status = 'CANCELLED') as cancelled
                      from task t cross join bounds b
                     where t.owner_id = :ownerId
                       and t.status <> 'OPEN'
                       and (t.closed_at at time zone b.tz)::date >= b.first_day - 27
                     group by 1
                ),
                joined as (
                    select s.day,
                           coalesce(c.work_done, 0) as work_done,
                           coalesce(c.personal_done, 0) as personal_done,
                           coalesce(c.cancelled, 0) as cancelled
                      from spine s left join closed c on c.day = s.day
                ),
                windowed as (
                    select j.day, j.work_done, j.personal_done, j.cancelled,
                           round(avg(j.work_done + j.personal_done)
                                 over (order by j.day rows between 6 preceding and current row), 2) as trailing7,
                           round(avg(j.work_done + j.personal_done)
                                 over (order by j.day rows between 27 preceding and current row), 2) as trailing28
                      from joined j
                )
                select w.* from windowed w
                 where w.day >= (select first_day from bounds)
                 order by w.day
                """)
                .param("ownerId", ownerId)
                .param("tz", tz)
                .param("days", days)
                .query((rs, i) -> new ThroughputPoint(
                        rs.getObject("day", LocalDate.class),
                        rs.getInt("work_done"),
                        rs.getInt("personal_done"),
                        rs.getInt("cancelled"),
                        rs.getDouble("trailing7"),
                        rs.getDouble("trailing28")))
                .list();
    }

    // ---- 2. Cycle time --------------------------------------------------

    public record CycleTimeRow(String dimension, String bucket, long count,
                               Double p50Seconds, Double p90Seconds) {}

    /**
     * Median and p90 of created-to-closed, overall and split two ways.
     *
     * <p>Never a mean. One task left open for eight months moves an average
     * enough to make it meaningless, while the median barely notices.
     *
     * <p>DONE only. Cancelled work has a duration but it measures how long
     * something sat before being abandoned, which is a different question and
     * would quietly inflate this one.
     */
    public List<CycleTimeRow> cycleTime(UUID ownerId, int days) {
        return db.sql("""
                with done as (
                    select priority, context,
                           extract(epoch from (closed_at - created_at)) as seconds
                      from task
                     where owner_id = :ownerId
                       and status = 'DONE'
                       and closed_at >= now() - make_interval(days => cast(:days as int))
                )
                select 'OVERALL' as dimension, '' as bucket, count(*) as n,
                       percentile_cont(0.5) within group (order by seconds) as p50,
                       percentile_cont(0.9) within group (order by seconds) as p90
                  from done
                union all
                select 'PRIORITY', 'P' || priority::text, count(*),
                       percentile_cont(0.5) within group (order by seconds),
                       percentile_cont(0.9) within group (order by seconds)
                  from done group by priority
                union all
                select 'CONTEXT', context, count(*),
                       percentile_cont(0.5) within group (order by seconds),
                       percentile_cont(0.9) within group (order by seconds)
                  from done group by context
                 order by 1, 2
                """)
                .param("ownerId", ownerId)
                .param("days", days)
                .query((rs, i) -> {
                    double p50 = rs.getDouble("p50");
                    boolean p50Null = rs.wasNull();
                    double p90 = rs.getDouble("p90");
                    boolean p90Null = rs.wasNull();
                    return new CycleTimeRow(
                            rs.getString("dimension"), rs.getString("bucket"), rs.getLong("n"),
                            p50Null ? null : p50, p90Null ? null : p90);
                })
                .list();
    }

    // ---- 3. Net backlog -------------------------------------------------

    public record BacklogPoint(LocalDate week, int created, int closed, int net, int cumulative) {}

    /**
     * Created minus closed per local week, plus a running total.
     *
     * <p>The single most informative number in the application is whether that
     * net figure is positive. The cumulative column shows whether a bad week was
     * an outlier or a trend.
     */
    public List<BacklogPoint> backlog(UUID ownerId, String tz, int weeks) {
        return db.sql("""
                with bounds as (
                    select date_trunc('week', (now() at time zone cast(:tz as text)))::date as this_week,
                           (date_trunc('week', (now() at time zone cast(:tz as text)))::date
                                - make_interval(weeks => cast(:weeks as int) - 1))::date as first_week,
                           cast(:tz as text) as tz
                ),
                spine as (
                    select generate_series(b.first_week, b.this_week, interval '1 week')::date as week
                      from bounds b
                ),
                made as (
                    select date_trunc('week', (t.created_at at time zone b.tz))::date as week,
                           count(*) as created
                      from task t cross join bounds b
                     where t.owner_id = :ownerId
                       and date_trunc('week', (t.created_at at time zone b.tz))::date >= b.first_week
                     group by 1
                ),
                shut as (
                    select date_trunc('week', (t.closed_at at time zone b.tz))::date as week,
                           count(*) as closed
                      from task t cross join bounds b
                     where t.owner_id = :ownerId
                       and t.status <> 'OPEN'
                       and date_trunc('week', (t.closed_at at time zone b.tz))::date >= b.first_week
                     group by 1
                )
                select s.week,
                       coalesce(m.created, 0) as created,
                       coalesce(k.closed, 0) as closed,
                       coalesce(m.created, 0) - coalesce(k.closed, 0) as net,
                       sum(coalesce(m.created, 0) - coalesce(k.closed, 0))
                           over (order by s.week) as cumulative
                  from spine s
                  left join made m on m.week = s.week
                  left join shut k on k.week = s.week
                 order by s.week
                """)
                .param("ownerId", ownerId)
                .param("tz", tz)
                .param("weeks", weeks)
                .query((rs, i) -> new BacklogPoint(
                        rs.getObject("week", LocalDate.class),
                        rs.getInt("created"), rs.getInt("closed"),
                        rs.getInt("net"), rs.getInt("cumulative")))
                .list();
    }

    // ---- 4. Aging WIP ---------------------------------------------------

    public record AgingBucket(String bucket, int count) {}

    public record OpenTask(UUID id, String title, short priority, String context,
                           Instant createdAt, int ageDays) {}

    /**
     * Open tasks by age band.
     *
     * <p>The highest-value chart here for one person working alone: it is the
     * one that surfaces what is quietly rotting. Which is why
     * {@link #oldestOpen} exists alongside it - a bar saying "6 tasks over 30
     * days" is interesting, and the six titles are actionable.
     */
    public List<AgingBucket> aging(UUID ownerId) {
        return db.sql("""
                select case
                         when now() - created_at < interval '3 days'  then '0-2d'
                         when now() - created_at < interval '8 days'  then '3-7d'
                         when now() - created_at < interval '31 days' then '8-30d'
                         else '30d+'
                       end as bucket,
                       count(*) as n
                  from task
                 where owner_id = :ownerId and status = 'OPEN'
                 group by 1
                """)
                .param("ownerId", ownerId)
                .query((rs, i) -> new AgingBucket(rs.getString("bucket"), rs.getInt("n")))
                .list();
    }

    public List<OpenTask> oldestOpen(UUID ownerId, int limit) {
        return db.sql("""
                select id, title, priority, context, created_at,
                       floor(extract(epoch from (now() - created_at)) / 86400)::int as age_days
                  from task
                 where owner_id = :ownerId and status = 'OPEN'
                 order by created_at
                 limit :limit
                """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(AnalyticsRepository::mapOpenTask)
                .list();
    }

    /**
     * Open more than 14 days with nothing but a CREATED event.
     *
     * <p>Not merely old - untouched. A task edited last week is being worked on
     * slowly; one with no event since creation has been looked past every day
     * since, and is usually either badly written or something you have silently
     * decided not to do.
     */
    public List<OpenTask> stale(UUID ownerId, int days, int limit) {
        return db.sql("""
                select t.id, t.title, t.priority, t.context, t.created_at,
                       floor(extract(epoch from (now() - t.created_at)) / 86400)::int as age_days
                  from task t
                 where t.owner_id = :ownerId
                   and t.status = 'OPEN'
                   and t.created_at < now() - make_interval(days => cast(:days as int))
                   and not exists (
                       select 1 from task_event e
                        where e.task_id = t.id and e.type <> 'CREATED'
                   )
                 order by t.created_at
                 limit :limit
                """)
                .param("ownerId", ownerId)
                .param("days", days)
                .param("limit", limit)
                .query(AnalyticsRepository::mapOpenTask)
                .list();
    }

    // ---- weekly review --------------------------------------------------

    public record ContextCount(String context, long count) {}

    /** Closures in the last N days, split by context, done and cancelled separately. */
    public List<ContextCount> closedByContext(UUID ownerId, int days, String status) {
        return db.sql("""
                select context, count(*) as n
                  from task
                 where owner_id = :ownerId
                   and status = cast(:status as text)
                   and closed_at >= now() - make_interval(days => cast(:days as int))
                 group by context
                """)
                .param("ownerId", ownerId)
                .param("days", days)
                .param("status", status)
                .query((rs, i) -> new ContextCount(rs.getString("context"), rs.getLong("n")))
                .list();
    }

    public record Movement(long created, long closed) {}

    public Movement movement(UUID ownerId, int days) {
        return db.sql("""
                select
                  (select count(*) from task
                    where owner_id = :ownerId
                      and created_at >= now() - make_interval(days => cast(:days as int))) as created,
                  (select count(*) from task
                    where owner_id = :ownerId and status <> 'OPEN'
                      and closed_at >= now() - make_interval(days => cast(:days as int))) as closed
                """)
                .param("ownerId", ownerId)
                .param("days", days)
                .query((rs, i) -> new Movement(rs.getLong("created"), rs.getLong("closed")))
                .single();
    }

    private static OpenTask mapOpenTask(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OpenTask(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getShort("priority"),
                rs.getString("context"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getInt("age_days"));
    }
}
