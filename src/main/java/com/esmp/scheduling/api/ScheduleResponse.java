package com.esmp.scheduling.api;

import java.util.List;

/**
 * Full scheduling recommendation response.
 *
 * <p>Contains both a wave-grouped view (for dependency-ordered execution planning) and a flat
 * ranking (for simple ordered list display). Both views contain the same {@link ModuleSchedule}
 * records ordered by {@code waveNumber ASC, finalScore ASC}.
 *
 * @param waves        list of topological waves, each containing their modules sorted by score
 * @param flatRanking  all modules ordered by waveNumber ASC then finalScore ASC
 * @param generatedAt  ISO-8601 instant when this recommendation was produced
 * @param durationMs   total time taken to compute the recommendation in milliseconds
 */
public record ScheduleResponse(
    List<WaveGroup> waves,
    List<ModuleSchedule> flatRanking,
    String generatedAt,
    long durationMs
) {}
