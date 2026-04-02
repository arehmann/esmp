# Task: Add Class Search to Packages View

## Goal

Add a search input to the Packages page (`/packages`) that filters classes by name across all packages. When a class is found, show its curated description inline so users can browse and understand classes without navigating to the detail page.

## Current State

The Packages page has:
- Package treemap (size = class count, color = avg risk)
- Class table filtered by selected package (columns: Name, Stereotype, CC Max, Fan-In, Fan-Out, Risk)
- Data comes from `useHeatmap()` which returns `RiskHeatmapEntry[]` (no description fields)

## Changes

### 1. Search Input (top of class table)

Add a text input above the class table:
- Placeholder: "Search classes by name..."
- Filters `filteredClasses` by case-insensitive match on `simpleName` or `fqn`
- Works alongside the package filter (search within selected package, or across all if no package selected)
- Debounced (300ms) to avoid lag on 22K classes

### 2. Description Column in Class Table

Add a `Description` column after `Name`:
- Shows `curatedClassDescription` if available (truncated to ~80 chars with ellipsis)
- Falls back to first 80 chars of `businessDescription` in muted text
- Empty cell if neither exists
- This requires extending the heatmap endpoint or adding a lightweight batch endpoint

### 3. Backend: Extend Heatmap with Descriptions

Option A (recommended): Add `businessDescription` and `curatedClassDescription` to `RiskHeatmapEntry` record.
- Pro: single endpoint, no new queries
- Con: increases heatmap payload size

Option B: Separate batch endpoint `GET /api/lexicon/class-descriptions?fqns=...`
- Pro: keeps heatmap lean
- Con: extra round-trip, more complex frontend

**Recommendation: Option A** — the heatmap is already loaded for the treemap. Adding two nullable string fields is simpler than a second request. Most descriptions are null (only 1,629 have businessDescription, fewer have curated), so the payload increase is modest.

### 4. Hover Preview (optional enhancement)

When hovering over a class name in the table, show a tooltip with the full description (not truncated). Uses the existing shadcn Tooltip component.

## Files to Modify

### Backend
- `RiskHeatmapEntry.java` — add `String businessDescription, String curatedClassDescription`
- `RiskService.java` — update `mapNodeToHeatmapEntry()` to read both fields from node

### Frontend
- `esmp-dashboard/lib/types.ts` — add fields to `RiskHeatmapEntry` interface
- `esmp-dashboard/app/packages/page.tsx` — add search input + description column + hover tooltip

## Implementation Notes

- Search should work on the client side (all heatmap data is already loaded)
- Keep the table performant with 22K rows — consider virtualizing or limiting visible rows to 200 with a "showing X of Y" counter
- The search input should auto-focus when the page loads if no package is selected
