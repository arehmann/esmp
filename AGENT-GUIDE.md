# ESMP Agent Guide — Migration Intelligence for AI Assistants

You have access to ESMP (Enterprise Source Migration Platform) via MCP tools. ESMP has analyzed the entire legacy Vaadin 7 Java codebase and built a knowledge graph of every class, method, relationship, business term, and risk score. Use it to understand what you're migrating before writing any code.

## Available MCP Tools

### Context & Understanding

| Tool | When to use | Returns |
|------|-------------|---------|
| `getMigrationContext(classFqn)` | **First call for any class** — before reading source or planning changes | `businessDescription` (what the class does in business terms), dependency cone, risk scores (structural + domain), linked business terms with UI roles, re-ranked code chunks, business rules. Token-budgeted to ~8K. |
| `getDomainGlossary()` | **Once per session** — to understand the business domain before diving into code | Domain area overview (ORDER_MANAGEMENT, CONTRACT_MANAGEMENT, PRODUCTION, SALES, etc.), UI role distribution, top 30 business terms, abbreviation glossary (SC=Schedule Composition, AEP=Ad Edition Part, DS=Distribution Schedule, BP=Business Partner, etc.) |
| `getSourceCode(classFqn)` | When you need the actual Java source to rewrite | Full source text read from filesystem via the graph-stored path |
| `searchKnowledge(query, module?, stereotype?, topK?)` | When you don't have a specific FQN — find relevant code by semantic meaning | Ranked code chunks with similarity scores. Filter by module or stereotype (Service, Repository, VaadinView, etc.) |
| `getDependencyCone(classFqn, maxDepth?)` | To understand what a class depends on transitively | All reachable nodes via DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE edges |
| `browseDomainTerms(search?, criticality?, uiRole?, domainArea?, includeAll?)` | To find business terms by area or role. Default returns NLS-sourced terms only (real business vocabulary). | Compact term list (documentContext stripped for size). Use `uiRole` to filter: LABEL, MESSAGE, TOOLTIP, BUTTON, TITLE, ERROR, ENUM_DISPLAY |

### Risk & Planning

| Tool | When to use | Returns |
|------|-------------|---------|
| `getRiskAnalysis(classFqn?, module?, sortBy?, limit?)` | Assess risk before migrating. With FQN: detailed class risk. Without FQN: heatmap of riskiest classes. | Structural risk (complexity, fan-in/out, DB writes) + domain risk (criticality, security sensitivity, financial involvement, business rule density). Score 0.0-1.0. |
| `getMigrationPlan(classFqn)` | See what can be auto-migrated vs needs AI judgment | Automatable actions (type renames, import swaps) vs manual actions (Table→Grid, BeanFieldGroup→Binder). Automation score 0.0-1.0. |
| `getModuleMigrationSummary(module)` | Module-level assessment before starting a module | Class counts (fully automatable, partial, AI-only), transitive detection, coverage stats, top unmapped types |
| `getRecipeBookGaps()` | Find Vaadin 7 types that have no migration mapping yet | NEEDS_MAPPING rules sorted by usageCount — prioritize adding mappings for high-usage types |
| `validateSystemHealth()` | Before starting migration — confirm data integrity | 47 validation queries checking graph integrity, vector index alignment, risk score population |

### Execution

| Tool | When to use | Returns |
|------|-------------|---------|
| `applyMigrationRecipes(classFqn)` | Apply mechanical transforms (type renames, import swaps) | Unified diff + modified source. Does NOT write to disk — you handle file writes. Only applies automatable transforms; complex rewrites (Table→Grid, data binding) are left for you. |

## Recommended Workflow

### 1. Orient yourself (once per session)

```
getDomainGlossary()  →  understand the business, decode abbreviations
```

This returns domain areas, abbreviation expansions, and UI role distribution. Cache this mentally — you'll reference it throughout migration.

### 2. Assess a class before migrating

```
getMigrationContext(classFqn)  →  read businessDescription first
```

The `businessDescription` field tells you what the class does in 1-3 sentences (e.g., "ForeignSupplementPanel — Ad order management. UI: LABEL, MESSAGE. Terms: Supplement, Edition."). This is your starting point — don't skip it.

Key fields in the response:
- `businessDescription` — what the class does in business terms
- `domainTerms[].uiRole` — which Vaadin 24 component to use for each string
- `domainTerms[].definition` — German business meaning (this is a German enterprise product)
- `domainTerms[].documentContext` — excerpt from legacy documentation explaining the concept
- `riskAnalysis.enhancedRiskScore` — overall migration risk (0.0-1.0)
- `dependencyCone.coneNodes` — what this class touches transitively

### 3. Plan the migration

```
getMigrationPlan(classFqn)    →  what's automatable vs manual
getRiskAnalysis(classFqn)     →  how risky is this class
```

### 4. Apply mechanical transforms first

```
applyMigrationRecipes(classFqn)  →  get the diff for type renames, imports
```

Apply the diff, then handle the complex rewrites (Table→Grid, data binding, layout changes) yourself using the context from step 2.

### 5. Read source when needed

```
getSourceCode(classFqn)  →  full Java source
```

## Understanding the Data

### Domain Areas

The codebase is organized into business domains derived from NLS XML files:

| domainArea | Business function |
|-----------|-------------------|
| ORDER_MANAGEMENT | Ad orders, schedule compositions, pricing, supplements |
| CONTRACT_MANAGEMENT | Contracts, commissions, settlements |
| ADMINISTRATION | Users, rights, system configuration |
| PRODUCTION | Publishing production, editions, print |
| SALES | Sales representatives, commissions |
| REPORTING | Business reports, statistics |
| COMMON | Shared UI vocabulary (OK, Cancel, Save) |
| DATA_IMPORT | XML data import operations |
| GLOSSARY | Domain abbreviation definitions |

### UI Roles → Vaadin 24 Component Mapping

When migrating UI strings, the `uiRole` tells you which component to use:

| uiRole | Vaadin 7 pattern | Vaadin 24 equivalent |
|--------|------------------|---------------------|
| LABEL | `new Label(getNLS("lblX"))` | `field.setLabel(...)` or `button.setText(...)` |
| MESSAGE | `Window.Notification` | `Notification.show(...)` or `ConfirmDialog` |
| TOOLTIP | `setDescription(getNLS("ttX"))` | `Tooltip.forComponent(component)` |
| TITLE | `window.setCaption(...)` | `dialog.setHeaderTitle(...)` or `new H2(...)` |
| BUTTON | `new Button(getNLS("btnX"))` | `new Button(text)` |
| ERROR | error notification | `Notification` with `Lumo.ERROR` variant |
| ENUM_DISPLAY | `TypeText_*` in ComboBox | ComboBox item label via `setItemLabelGenerator()` |
| HELP_TEXT | tooltip or help popup | `field.setHelperText(...)` |

### Abbreviations

The codebase uses domain-specific abbreviations extensively. The glossary is in `getDomainGlossary().abbreviations`. Key ones:

| Abbrev | Meaning | Context |
|--------|---------|---------|
| SC | Schedule Composition | A single order line item within an edition |
| AEP | Ad Edition Part | Part of an advertisement assigned to a specific edition |
| DS | Distribution Schedule | Plan for how supplements are distributed |
| BP | Business Partner | Customer, agency, or advertiser entity |
| FS | Foreign Supplement | Physical paper insert distributed with newspaper |
| CO | Commercial Object | Top-level advertising order entity |
| NLS | National Language Support | Multilingual string resource system |
| BO | Business Object | Suffix on 200+ domain classes |
| PO | Persistent Object | Suffix on persistence layer classes |

### Risk Scores

Risk is scored 0.0-1.0 across 8 dimensions:

**Structural** (from code analysis):
- `complexitySum/Max` — cyclomatic complexity
- `fanIn/fanOut` — how many classes depend on / are depended on
- `dbWriteCount` — database mutation methods

**Domain** (from business context):
- `domainCriticality` — linked to high-criticality business terms
- `securitySensitivity` — security-related naming/annotations
- `financialInvolvement` — financial domain indicators
- `businessRuleDensity` — DEFINES_RULE edge count

`enhancedRiskScore` combines all 8 dimensions. Higher = riskier to migrate, needs more care.

## Token Budget Awareness

ESMP responses are designed to be compact:
- `getMigrationContext` is budgeted to ~8K tokens with truncation
- `browseDomainTerms` strips `documentContext` from list responses
- `businessDescription` is max 300 chars per class
- Use `getDomainGlossary()` once, not per-class

If you need more detail on a specific term, use `browseDomainTerms(search="termId")` to get the full record.

## Important Notes

- **German business context**: This is a German enterprise ad management product (alfa AdSuite). NLS `definition` fields are in German — they contain the actual business meaning. `displayName` is the English code-facing label.
- **getNLS() pattern**: The legacy code uses `getNLS("key")` calls to load localized strings at runtime. Each detected call is linked to its containing class via USES_TERM edges in the graph.
- **Curated terms are protected**: If a human has curated a business term (set `curated=true`), re-extraction will not overwrite it.
- **The graph knows more than the source**: ESMP's knowledge graph contains relationships (DEPENDS_ON, EXTENDS, CALLS, BINDS_TO, USES_TERM, DEFINES_RULE) that aren't obvious from reading a single file. Always check `getMigrationContext` before assuming you understand a class's role.
