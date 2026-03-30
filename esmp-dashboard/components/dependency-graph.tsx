"use client";

import Link from "next/link";
import { RiskBadge } from "./risk-badge";
import { simpleNameFromFqn } from "@/lib/utils";
import type { ConeNode } from "@/lib/types";
import type { RiskHeatmapEntry } from "@/lib/types";

interface DependencyListProps {
  coneNodes: ConeNode[];
  riskMap: Map<string, RiskHeatmapEntry>;
  limit?: number;
}

export function DependencyList({
  coneNodes,
  riskMap,
  limit = 15,
}: DependencyListProps) {
  // Sort by risk score (highest first), then alphabetically
  const sorted = [...coneNodes].sort((a, b) => {
    const ra = riskMap.get(a.fqn)?.enhancedRiskScore ?? -1;
    const rb = riskMap.get(b.fqn)?.enhancedRiskScore ?? -1;
    return rb - ra;
  });

  const shown = sorted.slice(0, limit);
  const remaining = sorted.length - limit;

  return (
    <div className="space-y-1">
      {shown.map((node) => {
        const risk = riskMap.get(node.fqn);
        return (
          <div key={node.fqn} className="flex items-center justify-between text-xs">
            <Link
              href={`/class/${node.fqn}`}
              className="truncate text-primary hover:underline"
            >
              → {simpleNameFromFqn(node.fqn)}
            </Link>
            {risk ? (
              <RiskBadge score={risk.enhancedRiskScore} />
            ) : (
              <span className="text-[10px] text-muted-foreground">—</span>
            )}
          </div>
        );
      })}
      {remaining > 0 && (
        <p className="text-[10px] text-muted-foreground">
          + {remaining} more dependencies
        </p>
      )}
      {coneNodes.length === 0 && (
        <p className="text-xs text-muted-foreground">No dependencies found</p>
      )}
    </div>
  );
}
