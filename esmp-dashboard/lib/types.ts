// === Risk API ===
export interface RiskHeatmapEntry {
  fqn: string;
  simpleName: string;
  packageName: string;
  complexitySum: number;
  complexityMax: number;
  fanIn: number;
  fanOut: number;
  hasDbWrites: boolean;
  dbWriteCount: number;
  structuralRiskScore: number;
  stereotypeLabels: string[];
  domainCriticality: number;
  securitySensitivity: number;
  financialInvolvement: number;
  businessRuleDensity: number;
  enhancedRiskScore: number;
}

export interface MethodComplexityEntry {
  methodId: string;
  simpleName: string;
  cyclomaticComplexity: number;
  parameterTypes: string[];
}

export interface RiskDetailResponse extends RiskHeatmapEntry {
  businessDescription: string | null;
  methods: MethodComplexityEntry[];
}

// === Graph API ===
export interface ConeNode {
  fqn: string;
  labels: string[];
}

export interface DependencyConeResponse {
  focalFqn: string;
  coneNodes: ConeNode[];
  coneSize: number;
}

export interface SearchEntry {
  fullyQualifiedName: string;
  simpleName: string;
  packageName: string;
  labels: string[];
}

export interface SearchResponse {
  query: string;
  results: SearchEntry[];
}

// === Lexicon API ===
export interface BusinessTermResponse {
  termId: string;
  displayName: string;
  definition: string;
  criticality: string;
  migrationSensitivity: string;
  synonyms: string[];
  curated: boolean;
  status: string;
  sourceType: string;
  primarySourceFqn: string;
  usageCount: number;
  relatedClassFqns: string[];
  uiRole: string | null;
  domainArea: string | null;
  nlsFileName: string | null;
  documentContext: string | null;
  documentSource: string | null;
}

export interface DomainAreaSummary {
  domainArea: string;
  termCount: number;
  topTerms: string[];
}

export interface TopTerm {
  termId: string;
  displayName: string;
  definition: string | null;
  uiRole: string | null;
  domainArea: string | null;
  usageCount: number;
}

export interface Abbreviation {
  abbreviation: string;
  expansion: string;
  evidence: string | null;
}

export interface DomainGlossaryResponse {
  domainAreas: DomainAreaSummary[];
  uiRoleCounts: Record<string, number>;
  topTerms: TopTerm[];
  abbreviations: Abbreviation[];
  totalNlsTerms: number;
  totalAllTerms: number;
}

// === Migration API ===
export interface MigrationActionEntry {
  actionType: string;
  source: string;
  target: string;
  automatable: string;
  context: string;
  isInherited: boolean;
  inheritedFrom: string | null;
  vaadinAncestor: string | null;
  pureWrapper: boolean | null;
  transitiveComplexity: number | null;
  overrideCount: number | null;
  ownVaadinCalls: number | null;
  migrationSteps: string[];
}

export interface MigrationPlan {
  classFqn: string;
  automatableActions: MigrationActionEntry[];
  manualActions: MigrationActionEntry[];
  totalActions: number;
  automatableCount: number;
  manualCount: number;
  automationScore: number;
  needsAiMigration: boolean;
}

export interface MigrationResult {
  classFqn: string;
  hasChanges: boolean;
  diff: string;
  modifiedSource: string;
  recipesApplied: number;
  remainingManual: MigrationActionEntry[];
  automationScore: number;
}

export interface ModuleMigrationSummary {
  module: string;
  totalClasses: number;
  classesWithActions: number;
  fullyAutomatableClasses: number;
  partiallyAutomatableClasses: number;
  needsAiOnlyClasses: number;
  averageAutomationScore: number;
  totalActions: number;
  totalAutomatableActions: number;
  transitiveClassCount: number;
  coverageByType: number;
  coverageByUsage: number;
  topGaps: string[];
}

// === Recipe Book API ===
export interface RecipeRule {
  id: string;
  category: string;
  source: string;
  target: string;
  actionType: string;
  automatable: string;
  context: string;
  migrationSteps: string[];
  status: string;
  usageCount: number;
  discoveredAt: string;
  isBase: boolean;
}

// === Extraction API ===

export interface ExtractionProgressEvent {
  module: string | null;
  stage: string;
  filesProcessed: number;
  totalFiles: number;
  message: string | null;
  durationMs: number | null;
}

export interface ExtractionTriggerResponse {
  jobId: string;
  status: string;
}

export interface IncrementalIndexResponse {
  classesExtracted: number;
  classesDeleted: number;
  classesSkipped: number;
  nodesCreated: number;
  nodesUpdated: number;
  edgesLinked: number;
  chunksReEmbedded: number;
  chunksDeleted: number;
  durationMs: number;
  errors: string[];
}

// === Dashboard aggregates (derived client-side) ===
export interface PackageGroup {
  packageName: string;
  shortName: string;
  classCount: number;
  avgEnhancedRisk: number;
  classes: RiskHeatmapEntry[];
}
