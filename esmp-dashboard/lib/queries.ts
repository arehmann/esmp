import { useQuery, useMutation } from "@tanstack/react-query";
import * as api from "./api";

// Module filter — derived from package third segment (de.alfa.openMedia.*)
const MODULE = "openMedia";
const STALE_30S = 30_000;

export function useHeatmap() {
  return useQuery({
    queryKey: ["heatmap", MODULE],
    queryFn: () => api.fetchHeatmap(undefined, 5000),
    staleTime: STALE_30S,
  });
}

export function useMigrationSummary() {
  return useQuery({
    queryKey: ["migrationSummary", MODULE],
    queryFn: () => api.fetchMigrationSummary(MODULE),
    staleTime: STALE_30S,
  });
}

export function useRecipeBook() {
  return useQuery({
    queryKey: ["recipeBook"],
    queryFn: () => api.fetchRecipeBook(),
    staleTime: STALE_30S,
  });
}

export function useRecipeGaps() {
  return useQuery({
    queryKey: ["recipeGaps"],
    queryFn: () => api.fetchRecipeGaps(),
    staleTime: STALE_30S,
  });
}

export function useRiskDetail(fqn: string) {
  return useQuery({
    queryKey: ["riskDetail", fqn],
    queryFn: () => api.fetchRiskDetail(fqn),
    staleTime: STALE_30S,
    enabled: !!fqn,
  });
}

export function useDependencyCone(fqn: string) {
  return useQuery({
    queryKey: ["dependencyCone", fqn],
    queryFn: () => api.fetchDependencyCone(fqn),
    staleTime: STALE_30S,
    enabled: !!fqn,
  });
}

export function useTermsByClass(fqn: string) {
  return useQuery({
    queryKey: ["termsByClass", fqn],
    queryFn: () => api.fetchTermsByClass(fqn),
    staleTime: STALE_30S,
    enabled: !!fqn,
  });
}

export function useMigrationPlan(fqn: string) {
  return useQuery({
    queryKey: ["migrationPlan", fqn],
    queryFn: () => api.fetchMigrationPlan(fqn),
    staleTime: STALE_30S,
    enabled: !!fqn,
  });
}

export function useMigrationPreview(fqn: string) {
  return useMutation({
    mutationFn: () => api.fetchMigrationPreview(fqn),
  });
}

export function useGraphSearch(name: string) {
  return useQuery({
    queryKey: ["graphSearch", name],
    queryFn: () => api.fetchGraphSearch(name),
    staleTime: STALE_30S,
    enabled: name.length >= 2,
  });
}
