package com.patobytes.tasks.analytics;

import com.patobytes.tasks.analytics.AnalyticsRepository.AgingBucket;
import com.patobytes.tasks.analytics.AnalyticsRepository.BacklogPoint;
import com.patobytes.tasks.analytics.AnalyticsRepository.ContextCount;
import com.patobytes.tasks.analytics.AnalyticsRepository.CycleTimeRow;
import com.patobytes.tasks.analytics.AnalyticsRepository.Movement;
import com.patobytes.tasks.analytics.AnalyticsRepository.OpenTask;
import com.patobytes.tasks.analytics.AnalyticsRepository.ThroughputPoint;
import com.patobytes.tasks.config.AppProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    /** Fixed order for the age bands. SQL returns them grouped, not sorted. */
    private static final List<String> AGE_BANDS = List.of("0-2d", "3-7d", "8-30d", "30d+");

    private static final int STALE_AFTER_DAYS = 14;

    private final AnalyticsRepository repo;
    private final AppProperties properties;

    public AnalyticsService(AnalyticsRepository repo, AppProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    private String tz() {
        return properties.timezone().getId();
    }

    @Transactional(readOnly = true)
    public List<ThroughputPoint> throughput(UUID ownerId, int days) {
        return repo.throughput(ownerId, tz(), days);
    }

    @Transactional(readOnly = true)
    public List<CycleTimeRow> cycleTime(UUID ownerId, int days) {
        return repo.cycleTime(ownerId, days);
    }

    @Transactional(readOnly = true)
    public List<BacklogPoint> backlog(UUID ownerId, int weeks) {
        return repo.backlog(ownerId, tz(), weeks);
    }

    public record Aging(List<AgingBucket> buckets, List<OpenTask> oldest) {}

    /**
     * Bands always come back complete, including the empty ones.
     *
     * <p>A chart that silently drops "30d+" because it happens to be zero this
     * week looks identical to one where the band was never measured, and the
     * axis shifts under the reader between visits.
     */
    @Transactional(readOnly = true)
    public Aging aging(UUID ownerId) {
        List<AgingBucket> found = repo.aging(ownerId);
        List<AgingBucket> complete = new ArrayList<>();
        for (String band : AGE_BANDS) {
            complete.add(found.stream()
                    .filter(b -> b.bucket().equals(band))
                    .findFirst()
                    .orElse(new AgingBucket(band, 0)));
        }
        return new Aging(complete, repo.oldestOpen(ownerId, 10));
    }

    public record WeeklyReview(
            List<ContextCount> done,
            List<ContextCount> cancelled,
            long created,
            long closed,
            long net,
            List<OpenTask> oldestOpen,
            List<OpenTask> stale,
            int staleAfterDays) {}

    /**
     * The page that actually gets read on a Monday morning.
     *
     * <p>A dashboard someone visits twice is worthless; this is four questions
     * answered in one screen. Deliberately over the last seven days rather than
     * the previous calendar week, so it says something useful whenever it is
     * opened rather than only on Mondays.
     */
    @Transactional(readOnly = true)
    public WeeklyReview weeklyReview(UUID ownerId) {
        Movement movement = repo.movement(ownerId, 7);
        List<OpenTask> oldest = repo.oldestOpen(ownerId, 5);
        List<OpenTask> stale = repo.stale(ownerId, STALE_AFTER_DAYS, 20);

        // The oldest-open list and the stale list overlap heavily by nature.
        // Showing the same task twice on one screen reads as a bug, so anything
        // already named above is dropped from the stale list.
        List<UUID> alreadyShown = oldest.stream().map(OpenTask::id).toList();
        List<OpenTask> staleOnly = stale.stream()
                .filter(t -> !alreadyShown.contains(t.id()))
                .sorted(Comparator.comparing(OpenTask::createdAt))
                .toList();

        return new WeeklyReview(
                repo.closedByContext(ownerId, 7, "DONE"),
                repo.closedByContext(ownerId, 7, "CANCELLED"),
                movement.created(),
                movement.closed(),
                movement.created() - movement.closed(),
                oldest,
                staleOnly,
                STALE_AFTER_DAYS);
    }
}
