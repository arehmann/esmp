# Task: Fill Curated Class Descriptions

## Goal

Populate `curatedClassDescription` for all migration-relevant Vaadin classes using LLM-generated natural language descriptions. These descriptions explain what each class does in business terms, not technical implementation details.

## Prerequisites

- ESMP running with extraction completed (22K classes, 7K NLS terms, 341K vector chunks)
- MCP server accessible at `http://localhost:8080/mcp/sse`
- `saveClassDescription` MCP tool available (13th tool)

## Strategy

Work in priority order — riskiest and most migration-relevant classes first.

### Wave 1: Top Vaadin UI Classes (est. ~200 classes)

These are the ones developers will migrate first. They have the most NLS terms and business logic.

```console
> Connect to ESMP via MCP. For each class, follow this exact workflow:
>
> 1. Call getMigrationContext(classFqn) — read the businessDescription, domainTerms,
>    dependencyCone, and riskAnalysis
> 2. Call getSourceCode(classFqn) — skim the class structure, key methods, inheritance
> 3. Write a 2-3 sentence description that answers:
>    - What business function does this class serve? (not "it's a Vaadin panel")
>    - What domain area does it belong to? (orders, contracts, customers, production)
>    - What user workflow does it support? (order entry, customer search, scheduling)
> 4. Call saveClassDescription(classFqn, description) to persist it
>
> Start with this query to get the target list:
> Call getRiskAnalysis(null, null, "enhanced", 200) to get the top 200 classes by risk.
> Filter to only classes with "vaadin" in the package name.
> Process them one by one.
```

### Wave 2: Business Object Layer (~500 classes)

Classes in `businessObjects` package — these are the domain model that Vaadin views manipulate.

```console
> Now do the businessObjects package. Use searchKnowledge("business object domain model", null, null, 50)
> to find the key BO classes, then iterate:
> 1. getMigrationContext(fqn) for context
> 2. Write description focused on: what business entity does this represent?
>    What operations does it support? What other BOs does it collaborate with?
> 3. saveClassDescription(fqn, description)
```

### Wave 3: Service and Infrastructure (~200 classes)

Services, repositories, and integration classes that the UI and BOs depend on.

```console
> Process service and infrastructure classes. Focus on:
> - What business operation does this service orchestrate?
> - What external systems does it integrate with?
> - What data does it manage?
```

## Quality Guidelines

**Good description:**
> "OrderPanel is the primary order entry screen for advertising orders. It manages the full order lifecycle — from customer selection and ad specification (size, color, placement) through pricing calculation and scheduling across multiple publication editions. It coordinates with ScheduleCompositionPanel for edition-level line items and BusinessPartnerPanel for advertiser/agency selection."

**Bad description:**
> "OrderPanel — ORDER_MANAGEMENT. UI: LABEL, MESSAGE, TITLE. Terms: Order, Schedule, Edition."

**Rules:**
- Use natural English, not field dumps
- Name specific business concepts (not "manages data")
- Reference related classes by simple name when relevant
- 2-3 sentences max — concise but informative
- Don't describe Vaadin 7 implementation details (that's what the migration actions show)

## Verification

After filling a batch, verify on the dashboard:
1. Navigate to a class detail page — curated description should show in white text
2. Call `getMigrationContext` via MCP — `businessDescription` field should contain the curated text
3. Spot-check 5-10 descriptions for accuracy and usefulness

## Tracking

Use the REST API to check progress:

```bash
# Count classes with curated descriptions
curl -s "http://localhost:8080/api/risk/heatmap?limit=1000" | grep -c "curatedClassDescription"

# Or query Neo4j via the dashboard's Neo4j browser (localhost:7474):
# MATCH (c:JavaClass) WHERE c.curatedClassDescription IS NOT NULL RETURN count(c)
```

## Endpoint Reference

```
# Save a description
PUT /api/lexicon/class-description/{fqn}
Body: {"description": "Natural language description..."}

# MCP tool (from AI agent)
saveClassDescription(classFqn, description)

# Read back (via risk detail)
GET /api/risk/class/{fqn}
→ response includes curatedClassDescription field
```
