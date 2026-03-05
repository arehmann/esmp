# Phase 7: Domain-Aware Risk Analysis - Context

**Gathered:** 2026-03-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Enhance the structural risk score with domain-specific dimensions: domain criticality (from business term linkage), security sensitivity, financial involvement, and business rule density. Produce an enhanced composite risk score that combines all structural and domain signals for migration prioritization. No new extraction visitors — this phase operates on existing graph data (USES_TERM, DEFINES_RULE edges, BusinessTermNode.criticality).

</domain>

<decisions>
## Implementation Decisions

### Security Detection Heuristics
- Use combined approach: keyword matching on class names + package paths + annotation detection
- Keyword list: Auth, Login, Security, Encrypt, Cipher, Credential, Token, Permission, Acl (and similar)
- Annotation detection: @Secured, @PreAuthorize, @RolesAllowed, Spring Security annotations from ClassNode.annotations[]
- Package path matching: classes in security/auth/crypto packages inherit a base security score
- Keyword list hardcoded as static constant (matches STEREOTYPE_LABELS pattern in RiskService)
- Security sensitivity is a graduated score (0.0-1.0) based on match density (annotation=0.5, name=0.3, both=0.8, etc.)

### Financial Detection Heuristics
- Same multi-signal pattern as security: name keywords + package paths + annotation matching
- Financial keywords: Payment, Invoice, Billing, Ledger, Account, Transaction, Currency, Tax (and similar)
- Graduated score (0.0-1.0) matching the security approach
- Direct matches only — no graph traversal to find financial-adjacent classes (composite score handles this via fan-in/fan-out)
- USES_TERM enrichment: if a class USES_TERM a BusinessTerm with financial keywords, boost its financial involvement score

### Enhanced Score Composition
- Extend existing RiskWeightConfig with 4 new domain weights (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity)
- Single formula with all 8 dimensions, weights sum to 1.0
- Default weight distribution: 60% structural / 40% domain (Claude tunes exact values)
- Store as new `enhancedRiskScore` property on ClassNode alongside existing `structuralRiskScore`
- Extend existing GET /api/risk/heatmap with new domain score fields + sortBy parameter for structural vs enhanced ordering
- Also store individual domain scores (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity) on ClassNode for transparency

### Criticality Aggregation
- Max criticality: take the highest criticality of any linked BusinessTerm. One High term = domain-critical class
- Work with existing criticality levels: High=1.0, Low=0.0 (no expansion needed)
- Classes with zero USES_TERM edges get domainCriticality score of 0.0
- Business rule density uses log-normalized count: log(1 + definesRuleCount), matching existing log normalization pattern

### Claude's Discretion
- Exact keyword lists for security and financial detection (use the specified terms as starting point)
- Exact match density weights for graduated scoring
- Score clamping/normalization approach
- Validation query design for the 3 new risk validation queries
- Neo4j index strategy for new properties

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- **RiskService** (`graph/application/RiskService.java`): Existing Cypher-based risk computation with fan-in/out + composite score. Domain risk computation should extend this service.
- **RiskWeightConfig** (`extraction/config/RiskWeightConfig.java`): `@ConfigurationProperties(prefix="esmp.risk.weight")` with 4 structural weights. Add 4 domain weights here.
- **RiskHeatmapEntry** (`graph/api/RiskHeatmapEntry.java`): Response record for heatmap. Extend with domain score fields.
- **RiskDetailResponse** (`graph/api/RiskDetailResponse.java`): Class detail response. Extend with domain breakdown.
- **RiskController** (`graph/api/RiskController.java`): Two endpoints. Extend with sortBy parameter.
- **RiskValidationQueryRegistry** (`graph/validation/RiskValidationQueryRegistry.java`): 3 existing queries. Add domain-aware risk queries.

### Established Patterns
- **Cypher-based computation**: All risk scores computed via Neo4j Cypher SET (not Java iteration). Domain scores should follow same pattern.
- **Log normalization**: `log(1 + x)` used for complexity/fan-in/fan-out. Apply to business rule density.
- **Configurable weights**: `@ConfigurationProperties` with getter/setter pattern. New weights follow same convention.
- **Pattern comprehension for counting**: `size([(c)-[:DEPENDS_ON]->(other) | other])` pattern used for fan-in/out. Use same for USES_TERM/DEFINES_RULE counting.
- **ValidationQueryRegistry extensibility**: protected constructor pattern from Phase 5. New domain risk queries follow same approach.

### Integration Points
- **ExtractionService pipeline**: `riskService.computeAndPersistRiskScores()` called after `linkingService.linkAllRelationships()`. Domain risk computation follows in same pipeline slot (or extends it).
- **ClassNode**: Add new properties (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity, enhancedRiskScore).
- **Neo4jSchemaInitializer**: Add index for enhancedRiskScore.
- **USES_TERM edges**: Already created by LinkingService — traversed by Cypher for criticality aggregation.
- **DEFINES_RULE edges**: Already created by LinkingService — counted by Cypher for business rule density.

</code_context>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches following established patterns.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 07-domain-aware-risk-analysis*
*Context gathered: 2026-03-05*
