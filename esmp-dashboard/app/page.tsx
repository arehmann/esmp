"use client";

import Link from "next/link";
import {
  Layers,
  Zap,
  Bot,
  Wrench,
  AlertCircle,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { KpiCard } from "@/components/kpi-card";
import { RiskBadge } from "@/components/risk-badge";
import { StereotypeBadges } from "@/components/stereotype-badges";
import { StackedBarChart } from "@/components/stacked-bar-chart";
import { HorizontalBarChart } from "@/components/horizontal-bar-chart";
import { useMigrationSummary, useHeatmap, useRecipeGaps } from "@/lib/queries";
import { groupByPackage, formatPercent } from "@/lib/utils";

export default function OverviewPage() {
  const { data: summary, isLoading: summaryLoading } = useMigrationSummary();
  const { data: heatmap, isLoading: heatmapLoading } = useHeatmap();
  const { data: gaps, isLoading: gapsLoading } = useRecipeGaps();

  const packages = heatmap
    ? groupByPackage(heatmap).sort((a, b) => b.classCount - a.classCount)
    : [];

  const topRisk = heatmap
    ? [...heatmap]
        .sort((a, b) => b.enhancedRiskScore - a.enhancedRiskScore)
        .slice(0, 20)
    : [];

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold">Overview</h2>

      {/* KPI Cards */}
      <div className="grid grid-cols-5 gap-4">
        <KpiCard
          title="Total Classes"
          value={summary?.totalClasses ?? "\u2014"}
          icon={Layers}
          loading={summaryLoading}
        />
        <KpiCard
          title="Automatable"
          value={
            summary
              ? summary.totalClasses > 0
                ? formatPercent(summary.fullyAutomatableClasses / summary.totalClasses)
                : "0.0%"
              : "\u2014"
          }
          subtitle={summary ? `${summary.fullyAutomatableClasses} classes` : undefined}
          icon={Zap}
          color="text-auto-full"
          loading={summaryLoading}
        />
        <KpiCard
          title="AI-Assisted"
          value={
            summary
              ? summary.totalClasses > 0
                ? formatPercent(
                    (summary.partiallyAutomatableClasses + summary.needsAiOnlyClasses) /
                      summary.totalClasses
                  )
                : "0.0%"
              : "\u2014"
          }
          icon={Bot}
          color="text-auto-ai"
          loading={summaryLoading}
        />
        <KpiCard
          title="Manual Rewrite"
          value={
            summary
              ? summary.totalClasses > 0
                ? formatPercent(
                    (summary.totalClasses -
                      summary.fullyAutomatableClasses -
                      summary.partiallyAutomatableClasses -
                      summary.needsAiOnlyClasses) /
                      summary.totalClasses
                  )
                : "0.0%"
              : "\u2014"
          }
          icon={Wrench}
          color="text-auto-manual"
          loading={summaryLoading}
        />
        <KpiCard
          title="Recipe Gaps"
          value={gaps?.length ?? "\u2014"}
          icon={AlertCircle}
          color="text-amber-500"
          loading={gapsLoading}
        />
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-2 gap-4">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">
              Migration Readiness by Package
            </CardTitle>
          </CardHeader>
          <CardContent>
            {heatmapLoading ? (
              <Skeleton className="h-[300px]" />
            ) : (
              <StackedBarChart packages={packages} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">Top Recipe Gaps</CardTitle>
          </CardHeader>
          <CardContent>
            {gapsLoading ? (
              <Skeleton className="h-[300px]" />
            ) : gaps && gaps.length > 0 ? (
              <HorizontalBarChart gaps={gaps} />
            ) : (
              <p className="py-12 text-center text-sm text-muted-foreground">
                No recipe gaps found
              </p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Top Risk Classes */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">Top Risk Classes</CardTitle>
        </CardHeader>
        <CardContent>
          {heatmapLoading ? (
            <Skeleton className="h-[300px]" />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="pb-2 pr-4">Name</th>
                    <th className="pb-2 pr-4">Package</th>
                    <th className="pb-2 pr-4">Stereotype</th>
                    <th className="pb-2 pr-4 text-right">CC</th>
                    <th className="pb-2 pr-4 text-right">DB Writes</th>
                    <th className="pb-2 text-right">Risk</th>
                  </tr>
                </thead>
                <tbody>
                  {topRisk.map((c) => (
                    <tr
                      key={c.fqn}
                      className="border-b border-border/50 hover:bg-accent/50"
                    >
                      <td className="py-2 pr-4">
                        <Link
                          href={`/class/${c.fqn}`}
                          className="text-primary hover:underline"
                        >
                          {c.simpleName}
                        </Link>
                      </td>
                      <td className="py-2 pr-4 text-xs text-muted-foreground">
                        {c.packageName}
                      </td>
                      <td className="py-2 pr-4">
                        <StereotypeBadges labels={c.stereotypeLabels} />
                      </td>
                      <td className="py-2 pr-4 text-right font-mono text-xs">
                        {c.complexityMax}
                      </td>
                      <td className="py-2 pr-4 text-right font-mono text-xs">
                        {c.dbWriteCount}
                      </td>
                      <td className="py-2 text-right">
                        <RiskBadge score={c.enhancedRiskScore} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
