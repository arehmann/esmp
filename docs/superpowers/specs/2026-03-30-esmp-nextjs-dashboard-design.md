# ESMP Migration Dashboard — Design Spec

## Overview

Replace the current Vaadin governance dashboard with a modern Next.js frontend focused on **adsuite-market module migration**. The dashboard serves two personas: migration lead (strategic decisions) and developer (class-level migration work). The primary migration workflow uses ESMP via MCP tools in Claude Code — this dashboard is the **command center for understanding the codebase** before and during migration.

## Decisions

- **Scope**: adsuite-market module deep-dive, not multi-module overview
- **Layout**: Sidebar navigation with collapsible package tree + wide content area
- **Class detail**: Everything visible on one page, no tabs — migration actions + diff on left, context (business terms, dependencies, risk) on right
- **Theme**: Dark only — professional, matches developer tooling (terminal, IDE, Claude Code)
- **Primary use**: Read-only intelligence + diff preview. Actual migration happens via MCP/Claude Code.

## Tech Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Framework | Next.js 15 (App Router) | SSR for fast initial load, client components for interactivity |
| Styling | Tailwind CSS 3.4 | Utility-first, dark theme via `tailwind.config.ts` `darkMode: "class"` |
| Components | shadcn/ui | Professional, accessible, composable (Table, Card, Badge, Dialog, Command, Sheet, Tooltip) |
| Charts | Recharts | Stacked bars, treemaps, bar charts — readable at every zoom level, responsive |
| Graph viz | React Flow | Dependency cone visualization with proper zoom/pan/minimap |
| Icons | Lucide React | Consistent icon set, included with shadcn/ui |
| Data fetching | TanStack Query (React Query) | Cache, refetch, loading states for all API calls |
| Search | shadcn Command (cmdk) | Cmd+K global search palette |

## Architecture

```
Browser (port 3000)
  └─ Next.js App Router
       ├─ /app/layout.tsx          — root layout with sidebar
       ├─ /app/page.tsx            — overview dashboard
       ├─ /app/packages/page.tsx   — package treemap + class tables
       ├─ /app/risk/page.tsx       — risk heatmap data table
       ├─ /app/migration/page.tsx  — migration readiness + actions
       ├─ /app/recipes/page.tsx    — recipe book + gaps
       ├─ /app/class/[...fqn]/page.tsx — class detail deep-dive
       └─ next.config.ts rewrites  — proxy /esmp-api/* → ESMP:8080

  └─ calls ESMP REST API (port 8080)
       └─ All 41+ existing endpoints + 1 new endpoint (see below)
```

No BFF layer. Next.js calls ESMP REST API directly. CORS is handled via Next.js `rewrites` in `next.config.ts` — all client-side API calls go to `/esmp-api/*` which proxies to the ESMP backend transparently. This avoids CORS issues without needing `@CrossOrigin` on the Spring Boot side.

```ts
// next.config.ts
const nextConfig = {
  async rewrites() {
    return [
      {
        source: '/esmp-api/:path*',
        destination: `${process.env.ESMP_API_URL || 'http://localhost:8080'}/api/:path*`,
      },
    ];
  },
};
```

### Data Flow

1. **Server components** fetch initial data via `fetch()` during SSR (overview KPIs, package list)
2. **Client components** use TanStack Query for interactive data (search, filters, drill-downs)
3. **No state duplication** — API responses are the source of truth, cached client-side with 30s stale time
4. **URL-driven state** — filters, selected package, sort order stored in URL search params for shareability

## Required ESMP API Changes

One new endpoint is needed. All other data is available from existing endpoints.

### New: Business terms by class FQN

The Class Detail page needs business terms associated with a specific class. The existing Lexicon API only supports lookup by term ID (reverse direction). A new endpoint provides the forward direction.

**Endpoint:** `GET /api/lexicon/by-class/{fqn}`
**Response:** `List<BusinessTermResponse>` — the business terms linked to the given class via USES_TERM edges
**Implementation:** Single Cypher query: `MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:USES_TERM]->(t:BusinessTerm) RETURN t`

This is a ~10-line addition to `LexiconController` and `LexiconService`.

## KPI Mapping to API DTOs

The Overview page KPI cards derive from `ModuleMigrationSummary`:

| KPI | Source field | Computation |
|-----|-------------|-------------|
| Total Classes | `classCount` | Direct |
| Automatable % | `fullyAutomatableClasses` | `fullyAutomatableClasses / classCount * 100` |
| AI-Assisted % | `partiallyAutomatableClasses` + `needsAiOnlyClasses` | `(partial + aiOnly) / classCount * 100` |
| Manual Rewrite % | computed | `(classCount - fullyAuto - partial - aiOnly) / classCount * 100` — classes with zero matched recipes |
| Recipe Gaps | `/api/migration/recipe-book/gaps` | `gaps.length` |

## Color Scheme Distinction

Two color schemes are used consistently throughout:

- **Risk colors** (used in treemap, risk heatmap, risk badges): green (< 1.0), amber (1.0–2.0), red (> 2.0) — based on `enhancedRiskScore`
- **Automation colors** (used in KPIs, stacked bars, migration actions): green (automatable), amber (AI-assisted), red (manual rewrite) — based on recipe coverage

These are different dimensions. A class can be low-risk (green treemap cell) but need manual migration (red automation badge) or vice versa.

## Pages

### 1. Overview (`/`)

The command center. Answers: "What's the state of adsuite-market migration?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ KPI Cards (5)                                       │
│ [Classes] [Automatable%] [AI-Assisted%] [Manual%] [Gaps] │
├──────────────────────────┬──────────────────────────┤
│ Migration Readiness      │ Top Recipe Gaps          │
│ by Package               │ (horizontal bar chart    │
│ (stacked horizontal bar) │  sorted by usage count)  │
├──────────────────────────┴──────────────────────────┤
│ Top Risk Classes (compact table, top 20)            │
│ Name | Package | Complexity | DB Writes | Risk      │
└─────────────────────────────────────────────────────┘
```

**KPI Cards:** See "KPI Mapping to API DTOs" section above.

**Data sources:** `/api/migration/summary?module=adsuite-market`, `/api/risk/heatmap?module=adsuite-market&limit=20`, `/api/migration/recipe-book/gaps`

### 2. Packages (`/packages`)

Browse adsuite-market's internal structure. Answers: "What's inside this module and where are the problems?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ Package Treemap                                     │
│ (size = class count, color = avg enhanced risk)     │
│ Click block → filters table below                   │
├─────────────────────────────────────────────────────┤
│ Class Table (filtered by selected package)          │
│ Name | Stereotype | Risk | Actions | Auto% | CC     │
│ Click row → /class/[fqn]                            │
└─────────────────────────────────────────────────────┘
```

**Treemap colors:** green (risk < 1.0), amber (1.0–2.0), red (> 2.0). Labels show package short name + class count. Uses risk colors (not automation colors).

**Data sources:** `/api/risk/heatmap?module=adsuite-market&limit=5000` (grouped by `packageName` client-side for treemap). Fetched once on page load, shared between treemap and table.

### 3. Risk Heatmap (`/risk`)

Sortable data table of all classes. Answers: "Which classes are the riskiest and why?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ Filters: [Package ▾] [Stereotype ▾] [Risk > slider] │
├─────────────────────────────────────────────────────┤
│ Data Table (virtualized for 2,308 rows)             │
│ Name | Package | Stereotype | CC | Fan-In | Fan-Out │
│ DB Writes | Structural Risk | Enhanced Risk         │
│                                                     │
│ Risk cells color-coded: green/amber/red             │
│ Sortable by any column                              │
│ Click row → /class/[fqn]                            │
└─────────────────────────────────────────────────────┘
```

**Stereotype column:** `stereotypeLabels` is a `List<String>` — render as multiple badges (e.g., `[Service] [VaadinView]`).

**Data source:** `/api/risk/heatmap?module=adsuite-market&limit=5000`

### 4. Migration (`/migration`)

Migration readiness overview. Answers: "How much can we automate and what's blocking us?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ Summary Cards                                       │
│ [Auto: N classes] [AI: N] [Manual: N] [No Recipe: N]│
├──────────────────────────┬──────────────────────────┤
│ Actions by Category      │ Unmapped Types           │
│ (accordion/collapsible)  │ (sorted by usage count)  │
│                          │                          │
│ ▸ COMPONENT (56 rules)   │ com.vaadin.ui.Table  142 │
│   TextField → flow.TF    │ com.vaadin.ui.Window  98 │
│   Button → flow.Button   │ com.vaadin.ui.Panel   76 │
│ ▸ DATA_BINDING (13)      │ com.vaadin.ui.Tree    52 │
│ ▸ SERVER (15)            │ ...                      │
│ ▸ JAVAX_JAKARTA (10)     │                          │
├──────────────────────────┴──────────────────────────┤
│ Classes Needing Manual Attention (table)            │
│ Name | Package | Actions | Manual Count | Reason    │
└─────────────────────────────────────────────────────┘
```

**Data sources:** `/api/migration/recipe-book`, `/api/migration/recipe-book/gaps`, `/api/migration/summary?module=adsuite-market`

### 5. Recipes (`/recipes`)

Recipe book browser. Answers: "What transformation rules do we have and what's missing?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ Tab bar: [All Rules] [Gaps Only] [Discovered]       │
├─────────────────────────────────────────────────────┤
│ Recipe Table                                        │
│ ID | Source Type | Target Type | Category |         │
│ Automatable | Status | Usage Count | Steps          │
│                                                     │
│ Color coding:                                       │
│   MAPPED (green) | NEEDS_MAPPING (amber) |          │
│   DISCOVERED (blue)                                 │
│                                                     │
│ Click row → expand to show:                         │
│   - Migration steps (numbered list)                 │
│   - Classes using this rule (linked to /class/[fqn])│
└─────────────────────────────────────────────────────┘
```

**Data sources:** `/api/migration/recipe-book`, `/api/migration/recipe-book/gaps`

### 6. Class Detail (`/class/[...fqn]`)

Developer deep-dive. Answers: "What do I need to know about this class to migrate it?"

**Layout:**
```
┌─────────────────────────────────────────────────────┐
│ Breadcrumb: adsuite-market › vaadin.ui › ClassName  │
├─────────────────────────────────────────────────────┤
│ Header: ClassName                                   │
│ FQN | [VaadinComponent] [HIGH RISK]     Risk: 3.12  │
├─────────┬──────────┬──────────┬─────────────────────┤
│ Risk    │ Complex. │ Actions  │ Automation %        │
│ 3.12    │ 1,496    │ 12       │ 67%                 │
├─────────┴──────────┴──────────┴─────────────────────┤
│                    │                                 │
│ MIGRATION ACTIONS  │  BUSINESS TERMS                │
│ (color-coded list) │  [Invoice] [Order] [Payment]   │
│ ✓ TextField→flow   │                                │
│ ✓ Button→flow      │  DEPENDENCIES (47 classes)     │
│ ⚡ Table→Grid       │  → OrderService (HIGH)         │
│ ✗ DragDrop handler │  → InvoicePanel (MED)          │
│                    │  → ClientManager (LOW)          │
│ DIFF PREVIEW       │  + 44 more...                  │
│ ┌────────────────┐ │                                │
│ │- import v7.TF  │ │  RISK BREAKDOWN               │
│ │+ import flow.TF│ │  Max CC: 68 | DB: 11 | Fan: 0 │
│ └────────────────┘ │                                │
│ [Preview Diff]     │                                │
└────────────────────┴────────────────────────────────┘
```

**Migration actions** are sorted: manual (red) first, then AI-assisted (amber), then auto (green). Click any action → diff preview updates below.

**Dependencies** show risk-colored badges. The dependency cone endpoint returns `ConeNode(fqn, labels)` — no `simpleName` or risk scores. The frontend derives `simpleName` by splitting FQN on `.` and taking the last segment. To show risk coloring, the frontend performs a **client-side join**: on page load, fetch `/api/risk/heatmap?limit=5000` (already cached from other pages via TanStack Query), then match cone FQNs to heatmap entries for risk coloring. Unmatched FQNs show gray (no risk data).

**Business terms** shown as tag cloud with criticality colors (high=amber, medium=blue, low=gray). Data from new `GET /api/lexicon/by-class/{fqn}` endpoint.

**Diff preview** is read-only: a "Preview Diff" button calls `POST /api/migration/preview/{fqn}` and renders the result. No "Apply" buttons — actual migration happens via MCP/Claude Code.

**Data sources:** `/api/migration/plan/{fqn}`, `POST /api/migration/preview/{fqn}`, `/api/risk/class/{fqn}`, `/api/graph/class/{fqn}/dependency-cone`, `GET /api/lexicon/by-class/{fqn}` (new), `/api/risk/heatmap` (cached, for dependency risk join)

## Sidebar

```
┌──────────────────────┐
│ 🎯 ESMP              │
│ adsuite-market        │
│                       │
│ NAVIGATION            │
│ 📊 Overview           │
│ 📦 Packages           │
│ ⚠️  Risk Heatmap      │
│ 🔄 Migration          │
│ 📖 Recipes            │
│                       │
│ PACKAGES              │
│ ▸ vaadin.ui (356)     │
│ ▸ vaadin.ui.panel (89)│
│ ▸ vaadin.ui.widget(45)│
│ ▸ vaadin.validation   │
│ ▸ vaadin.order (32)   │
│ ▸ ...                 │
│                       │
│ ─────────────────     │
│ ⌨ Cmd+K Search        │
└──────────────────────┘
```

- Collapsible to icon-only mode (48px wide) for maximum content space
- Package tree populated from `/api/risk/heatmap?module=adsuite-market&limit=5000` on app init — grouped by `packageName`, sorted by class count descending
- Clicking a package filters the current page to that package scope
- Active page is highlighted with accent color

## Visual Design Principles

- **Dark theme**: `slate-900` background, `slate-800` cards, `slate-700` borders — matches VS Code/terminal aesthetic
- **Accent color**: `blue-500` for interactive elements, navigation highlights
- **Risk colors**: `green-500` (< 1.0), `amber-500` (1.0–2.0), `red-500` (> 2.0) — based on `enhancedRiskScore`
- **Automation colors**: `green-500` (auto), `amber-500` (AI-assisted), `red-500` (manual) — based on recipe coverage
- **Typography**: Inter font, `text-sm` for data density, `text-xs` for labels
- **Spacing**: Tight but breathable — 16px card padding, 8px gaps between elements
- **Animations**: Subtle — 150ms transitions on hover/focus, no gratuitous motion
- **Data density**: Information-rich without clutter. Every pixel should earn its place.
- **Professional signals**: Consistent border-radius (8px cards, 4px badges), subtle shadows, proper alignment grid

## Component Library (shadcn/ui usage)

| Component | Usage |
|-----------|-------|
| `Card` | KPI cards, chart containers, class header |
| `Table` | Risk heatmap, recipe list, class tables |
| `Badge` | Stereotype labels (multiple per class), risk levels, automation status |
| `Command` | Global Cmd+K search palette |
| `Collapsible` | Sidebar package tree, accordion sections |
| `Sheet` | Mobile sidebar overlay |
| `Tooltip` | Hover info on truncated text, chart data points |
| `Dialog` | Expanded diff preview |
| `Tabs` | Recipe page sub-views (All/Gaps/Discovered) |
| `Slider` | Risk threshold filter |
| `Select` | Package/stereotype filter dropdowns |
| `Skeleton` | Loading states for all data-fetching components |

## Error and Empty States

| Scenario | Behavior |
|----------|----------|
| ESMP API unreachable | Full-page error with retry button: "Cannot reach ESMP backend at {url}. Is the server running?" |
| No classes in module | Empty state illustration: "No classes found. Run extraction first." with link to extraction docs |
| Class has no migration actions | Show "No migration actions detected" with gray placeholder in the actions panel |
| Recipe book empty | Show "Recipe book not loaded" with instructions to check seed file |
| Diff preview fails | Inline error in diff panel: "Preview failed: {error}" — don't break the rest of the page |
| Search returns no results | "No classes matching '{query}'" in the command palette |
| Loading any data | `Skeleton` shimmer placeholders matching the expected layout shape |

## API Integration

**Key endpoints per page:**

| Page | Primary Endpoints |
|------|-------------------|
| Overview | `GET /api/migration/summary?module=adsuite-market`, `GET /api/risk/heatmap?module=adsuite-market&limit=20`, `GET /api/migration/recipe-book/gaps` |
| Packages | `GET /api/risk/heatmap?module=adsuite-market&limit=5000` (grouped client-side) |
| Risk | `GET /api/risk/heatmap?module=adsuite-market&limit=5000` |
| Migration | `GET /api/migration/recipe-book`, `GET /api/migration/recipe-book/gaps`, `GET /api/migration/summary?module=adsuite-market` |
| Recipes | `GET /api/migration/recipe-book`, `GET /api/migration/recipe-book/gaps` |
| Class Detail | `GET /api/migration/plan/{fqn}`, `POST /api/migration/preview/{fqn}`, `GET /api/risk/class/{fqn}`, `GET /api/graph/class/{fqn}/dependency-cone`, `GET /api/lexicon/by-class/{fqn}` (new) |
| Sidebar | `GET /api/risk/heatmap?module=adsuite-market&limit=5000` (on app init, shared with pages) |

**Caching strategy (TanStack Query):**
- `staleTime: 30_000` (30s) for heatmap, summary, recipe book — data doesn't change during a session
- `staleTime: 0` for diff previews — always fresh
- `gcTime: 300_000` (5 min) garbage collection
- **Shared queries**: The `risk/heatmap?limit=5000` query key is shared across Sidebar, Packages, Risk, and Class Detail pages — fetched once, reused everywhere

## Project Structure

```
esmp-dashboard/
├── app/
│   ├── globals.css              # Tailwind imports + dark theme variables
│   ├── layout.tsx               # Root layout + sidebar + QueryProvider
│   ├── page.tsx                 # Overview
│   ├── packages/page.tsx        # Package treemap
│   ├── risk/page.tsx            # Risk heatmap table
│   ├── migration/page.tsx       # Migration readiness
│   ├── recipes/page.tsx         # Recipe book
│   └── class/[...fqn]/page.tsx  # Class detail
├── components/
│   ├── ui/                      # shadcn/ui components
│   ├── sidebar.tsx              # App sidebar with package tree
│   ├── kpi-card.tsx             # Reusable KPI metric card
│   ├── risk-badge.tsx           # Color-coded risk badge
│   ├── automation-badge.tsx     # Color-coded automation level badge
│   ├── migration-action.tsx     # Single migration action row
│   ├── diff-viewer.tsx          # Code diff display
│   ├── package-treemap.tsx      # Recharts treemap
│   ├── stacked-bar.tsx          # Migration readiness chart
│   ├── dependency-graph.tsx     # React Flow graph
│   └── search-command.tsx       # Cmd+K search palette
├── lib/
│   ├── api.ts                   # ESMP API client (typed fetch wrappers via /esmp-api/*)
│   ├── types.ts                 # TypeScript types matching API DTOs
│   ├── queries.ts               # TanStack Query hooks (useHeatmap, useMigrationPlan, etc.)
│   └── utils.ts                 # Risk color helpers, automation color helpers, formatters
├── tailwind.config.ts
├── next.config.ts               # rewrites for API proxy
└── package.json
```

## Deployment

The Next.js app runs as a separate container alongside the existing ESMP stack.

```yaml
# Addition to docker-compose.full.yml
esmp-dashboard:
  build: ./esmp-dashboard
  ports:
    - "3000:3000"
  environment:
    ESMP_API_URL: http://esmp:8080
  depends_on:
    esmp:
      condition: service_healthy
```

For development: `npm run dev` with `ESMP_API_URL=http://localhost:8080`.

## Out of Scope

- User authentication / multi-tenancy
- Real-time extraction progress (SSE) — use the existing API directly if needed
- Editing recipe rules from the UI (read-only for now)
- Applying migration recipes from the UI (read-only diff preview only)
- Multi-module comparison views
- Exporting reports to PDF/CSV
- Mobile-optimized responsive layout (desktop-first, minimum 1280px)
