package com.esmp.mcp.api;

import com.esmp.graph.api.BusinessTermResponse;
import com.esmp.graph.api.ClassStructureResponse;
import com.esmp.graph.api.DependencyConeResponse;
import com.esmp.graph.api.InheritanceChainResponse;
import com.esmp.graph.api.RiskDetailResponse;
import com.esmp.rag.api.ContextChunk;
import java.util.List;

/**
 * Assembled migration context for a focal Java class.
 *
 * <p>Aggregates all ESMP knowledge signals into a single response record that can be served to an
 * AI assistant via an MCP tool. Code chunks use {@link ContextChunk} (from {@code com.esmp.rag.api})
 * rather than raw vector search results so that the weighted re-ranking computed by
 * {@link com.esmp.rag.application.RagService} (vectorSimilarity + graphProximity + riskScore) is
 * preserved in the final output.
 *
 * @param classFqn            fully-qualified class name of the focal class
 * @param businessDescription concise English description of what this class does in business terms
 * @param classStructure      class structure including superClass, interfaces, dependencies, methods
 * @param inheritanceChain    full ancestor chain via EXTENDS with implemented interfaces
 * @param dependencyCone      transitive dependency cone (null if graph service failed)
 * @param riskAnalysis        per-class risk detail with domain scores (null if risk service failed)
 * @param domainTerms         business terms linked to this class via USES_TERM edges
 * @param businessRules       display names of terms linked via DEFINES_RULE edges
 * @param codeChunks          re-ranked code chunks from RagService (empty if RAG failed)
 * @param truncated           true if token budget was exceeded and codeChunks were truncated
 * @param truncatedItems      number of code chunks dropped during truncation
 * @param contextCompleteness fraction of services that contributed successfully (0.0 to 1.0)
 * @param warnings            degradation warnings from failed downstream services
 * @param durationMs          total assembly duration in milliseconds
 */
public record MigrationContext(
    String classFqn,
    String businessDescription,
    ClassStructureResponse classStructure,
    InheritanceChainResponse inheritanceChain,
    DependencyConeResponse dependencyCone,
    RiskDetailResponse riskAnalysis,
    List<BusinessTermResponse> domainTerms,
    List<String> businessRules,
    List<ContextChunk> codeChunks,
    boolean truncated,
    int truncatedItems,
    double contextCompleteness,
    List<AssemblerWarning> warnings,
    long durationMs) {}
