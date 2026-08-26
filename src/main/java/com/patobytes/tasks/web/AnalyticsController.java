package com.patobytes.tasks.web;

import com.patobytes.tasks.analytics.AnalyticsRepository.BacklogPoint;
import com.patobytes.tasks.analytics.AnalyticsRepository.CycleTimeRow;
import com.patobytes.tasks.analytics.AnalyticsRepository.ThroughputPoint;
import com.patobytes.tasks.analytics.AnalyticsService;
import com.patobytes.tasks.analytics.AnalyticsService.Aging;
import com.patobytes.tasks.analytics.AnalyticsService.WeeklyReview;
import com.patobytes.tasks.config.AppProperties;
import com.patobytes.tasks.user.CurrentUserService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tier 1 reports plus the weekly review.
 *
 * <p>Separate endpoints rather than one dashboard payload: nine more reports
 * arrive in M4 and M5, and a single response carrying all thirteen would be
 * slow to produce and impossible to cache selectively.
 *
 * <p>Window sizes are clamped. They are read from a query string, and a request
 * for a million days is a request to build a million-row spine in memory.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final CurrentUserService currentUser;
    private final AppProperties properties;

    public AnalyticsController(AnalyticsService analytics, CurrentUserService currentUser,
                               AppProperties properties) {
        this.analytics = analytics;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    private UUID owner() {
        return currentUser.require().getId();
    }

    public record Throughput(List<ThroughputPoint> points, String timezone) {}

    @GetMapping("/throughput")
    public Throughput throughput(@RequestParam(defaultValue = "90") int days) {
        return new Throughput(
                analytics.throughput(owner(), Math.clamp(days, 7, 365)),
                properties.timezone().getId());
    }

    @GetMapping("/cycle-time")
    public List<CycleTimeRow> cycleTime(@RequestParam(defaultValue = "180") int days) {
        return analytics.cycleTime(owner(), Math.clamp(days, 7, 730));
    }

    @GetMapping("/backlog")
    public List<BacklogPoint> backlog(@RequestParam(defaultValue = "12") int weeks) {
        return analytics.backlog(owner(), Math.clamp(weeks, 2, 104));
    }

    @GetMapping("/aging")
    public Aging aging() {
        return analytics.aging(owner());
    }

    @GetMapping("/weekly-review")
    public WeeklyReview weeklyReview() {
        return analytics.weeklyReview(owner());
    }
}
