import type {
  RiskHeatmapEntry, RiskDetailResponse, DependencyConeResponse,
  SearchResponse, BusinessTermResponse, MigrationPlan,
  MigrationResult, ModuleMigrationSummary, RecipeRule,
  ExtractionTriggerResponse, DomainGlossaryResponse,
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
  module?: string, limit = 5000, sortBy: "enhanced" | "structural" = "enhanced"
): Promise<RiskHeatmapEntry[]> {
  const params = new URLSearchParams({ limit: String(limit), sortBy });
  if (module) params.set("module", module);
  return fetchJson(`${BASE}/risk/heatmap?${params}`);
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

export function fetchLexicon(params?: {
  sourceType?: string;
  criticality?: string;
  search?: string;
  uiRole?: string;
  domainArea?: string;
  nlsOnly?: boolean;
  limit?: number;
}): Promise<BusinessTermResponse[]> {
  const p = new URLSearchParams();
  if (params?.sourceType) p.set("sourceType", params.sourceType);
  if (params?.criticality) p.set("criticality", params.criticality);
  if (params?.search) p.set("search", params.search);
  if (params?.uiRole) p.set("uiRole", params.uiRole);
  if (params?.domainArea) p.set("domainArea", params.domainArea);
  if (params?.nlsOnly) p.set("nlsOnly", "true");
  p.set("limit", String(params?.limit ?? 500));
  return fetchJson(`${BASE}/lexicon?${p}`);
}

export function fetchDomainGlossary(): Promise<DomainGlossaryResponse> {
  return fetchJson(`${BASE}/lexicon/glossary`);
}

export function fetchMigrationPlan(fqn: string): Promise<MigrationPlan> {
  return fetchJson(`${BASE}/migration/plan/${fqn}`);
}

export function fetchMigrationPreview(fqn: string): Promise<MigrationResult> {
  return fetchJson(`${BASE}/migration/preview/${fqn}`, { method: "POST" });
}

export function fetchMigrationSummary(module?: string): Promise<ModuleMigrationSummary> {
  const url = module
    ? `${BASE}/migration/summary?module=${encodeURIComponent(module)}`
    : `${BASE}/migration/summary`;
  return fetchJson(url);
}

export function fetchRecipeBook(): Promise<RecipeRule[]> {
  return fetchJson(`${BASE}/migration/recipe-book`);
}

export function fetchRecipeGaps(): Promise<RecipeRule[]> {
  return fetchJson(`${BASE}/migration/recipe-book/gaps`);
}

// --- Extraction ---

export function triggerExtraction(
  sourceRoot: string,
  classpathFile?: string
): Promise<ExtractionTriggerResponse> {
  return fetchJson(`${BASE}/extraction/trigger`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sourceRoot, classpathFile: classpathFile || undefined }),
  });
}

export function triggerIncrementalIndex(
  changedFiles: string[],
  deletedFiles: string[],
  sourceRoot: string,
  classpathFile?: string
) {
  return fetchJson(`${BASE}/indexing/incremental`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ changedFiles, deletedFiles, sourceRoot, classpathFile }),
  });
}

export function getSourceStatus(): Promise<{ sourceRoot: string; resolved: boolean; strategy: string }> {
  return fetchJson(`${BASE}/source/status`);
}

export function getValidationReport() {
  return fetchJson(`${BASE}/graph/validation`);
}
