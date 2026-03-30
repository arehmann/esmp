import type {
  RiskHeatmapEntry, RiskDetailResponse, DependencyConeResponse,
  SearchResponse, BusinessTermResponse, MigrationPlan,
  MigrationResult, ModuleMigrationSummary, RecipeRule,
} from "./types";

const BASE = "/esmp-api";

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    throw new Error(`API ${res.status}: ${res.statusText} — ${url}`);
  }
  return res.json();
}

export function fetchHeatmap(
  module: string, limit = 5000, sortBy: "enhanced" | "structural" = "enhanced"
): Promise<RiskHeatmapEntry[]> {
  return fetchJson(`${BASE}/risk/heatmap?module=${encodeURIComponent(module)}&limit=${limit}&sortBy=${sortBy}`);
}

export function fetchRiskDetail(fqn: string): Promise<RiskDetailResponse> {
  return fetchJson(`${BASE}/risk/class/${fqn}`);
}

export function fetchDependencyCone(fqn: string): Promise<DependencyConeResponse> {
  return fetchJson(`${BASE}/graph/class/${fqn}/dependency-cone`);
}

export function fetchGraphSearch(name: string): Promise<SearchResponse> {
  return fetchJson(`${BASE}/graph/search?name=${encodeURIComponent(name)}`);
}

export function fetchTermsByClass(fqn: string): Promise<BusinessTermResponse[]> {
  return fetchJson(`${BASE}/lexicon/by-class/${fqn}`);
}

export function fetchMigrationPlan(fqn: string): Promise<MigrationPlan> {
  return fetchJson(`${BASE}/migration/plan/${fqn}`);
}

export function fetchMigrationPreview(fqn: string): Promise<MigrationResult> {
  return fetchJson(`${BASE}/migration/preview/${fqn}`, { method: "POST" });
}

export function fetchMigrationSummary(module: string): Promise<ModuleMigrationSummary> {
  return fetchJson(`${BASE}/migration/summary?module=${encodeURIComponent(module)}`);
}

export function fetchRecipeBook(): Promise<RecipeRule[]> {
  return fetchJson(`${BASE}/migration/recipe-book`);
}

export function fetchRecipeGaps(): Promise<RecipeRule[]> {
  return fetchJson(`${BASE}/migration/recipe-book/gaps`);
}
