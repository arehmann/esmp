# Class Detail Enrichment — Business Context + Rich Terms + MCP Prompt

## Scope

Enhance the existing `/class/[...fqn]` page to surface the new lexicon enrichment data (uiRole, domainArea, documentContext, businessDescription) that was added in Phase C.

## Changes

### 1. Business Context Card (new, top of right column)

Shows the `businessDescription` field from the ClassNode (fetched via new `/api/lexicon/class-context/{fqn}` or by extending an existing endpoint). Displays:
- `businessDescription` text (1-3 sentences, max 300 chars)
- `domainArea` badge (color-coded by area)
- Count of linked NLS terms

Falls back to "No business context available" if `businessDescription` is null (class has no linked NLS terms).

### 2. Expanded Business Terms Card (replaces badge-only view)

Currently: tiny badges with tooltip showing definition.

New: scrollable list/accordion where each term shows:
- `displayName` (EN) bold + `definition` (DE) muted, side by side
- `uiRole` badge (color-coded: LABEL=blue, MESSAGE=amber, BUTTON=green, TOOLTIP=purple, ERROR=red, TITLE=slate, ENUM_DISPLAY=cyan)
- `domainArea` small text
- Expandable `documentContext` (click to reveal legacy doc excerpt + `documentSource` reference)
- `sourceType` indicator (NLS_LABEL, NLS_MESSAGE, etc.)

Sort: NLS terms first (by usageCount desc), CLASS_NAME/ENUM terms at bottom or hidden by default.

### 3. Copy MCP Prompt Button (header area)

Small button next to the class name that copies:
```
Use getMigrationContext("de.alfa.openMedia...") to understand this class, then plan the Vaadin 7 to 24 migration.
```
to clipboard. Toast notification on copy.

## Data Flow

- `useTermsByClass(fqn)` already returns `BusinessTermResponse[]` with all enrichment fields
- Need: `businessDescription` from ClassNode — add to risk detail response or create a lightweight endpoint
- No new backend changes required if we piggyback `businessDescription` on the existing risk detail endpoint

## Files Modified

- `esmp-dashboard/app/class/[...fqn]/page.tsx` — layout changes, new cards
- `esmp-dashboard/components/business-term-tags.tsx` — rewrite to rich list
- `esmp-dashboard/lib/types.ts` — no changes (fields already defined)
- `esmp-dashboard/lib/api.ts` — possibly add businessDescription fetch
- Backend: extend `RiskDetailResponse` or `GET /api/risk/class/{fqn}` to include `businessDescription`
