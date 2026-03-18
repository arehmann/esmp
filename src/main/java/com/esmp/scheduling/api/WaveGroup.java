package com.esmp.scheduling.api;

import java.util.List;

/**
 * A group of modules that can be migrated in the same topological wave.
 *
 * <p>Modules within a wave have no inter-dependencies on each other (or are in a circular
 * dependency cycle, assigned to the final wave). They are ordered by {@code finalScore} ascending
 * so the safest module in each wave appears first.
 *
 * @param waveNumber the wave ordinal (1-based; higher waves depend on lower waves)
 * @param modules    modules in this wave, ordered by finalScore ascending
 */
public record WaveGroup(int waveNumber, List<ModuleSchedule> modules) {}
