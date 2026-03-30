"use client";

import { Zap, Bot, Wrench, HelpCircle } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { AutomationBadge } from "@/components/automation-badge";
import { KpiCard } from "@/components/kpi-card";
import {
  useMigrationSummary,
  useRecipeBook,
  useRecipeGaps,
} from "@/lib/queries";

export default function MigrationPage() {
  const { data: summary, isLoading: summaryLoading } = useMigrationSummary();
  const { data: recipes, isLoading: recipesLoading } = useRecipeBook();
  const { data: gaps, isLoading: gapsLoading } = useRecipeGaps();

  const manualCount = summary
    ? summary.totalClasses -
      summary.fullyAutomatableClasses -
      summary.partiallyAutomatableClasses -
      summary.needsAiOnlyClasses
    : 0;

  // Group recipes by category
  const recipesByCategory = recipes
    ? recipes.reduce(
        (acc, r) => {
          (acc[r.category] ??= []).push(r);
          return acc;
        },
        {} as Record<string, typeof recipes>
      )
    : {};

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold">Migration</h2>

      {/* Summary cards */}
      <div className="grid grid-cols-4 gap-4">
        <KpiCard
          title="Automatable"
          value={summary?.fullyAutomatableClasses ?? "\u2014"}
          subtitle="classes"
          icon={Zap}
          color="text-auto-full"
          loading={summaryLoading}
        />
        <KpiCard
          title="AI-Assisted"
          value={
            summary
              ? summary.partiallyAutomatableClasses +
                summary.needsAiOnlyClasses
              : "\u2014"
          }
          subtitle="classes"
          icon={Bot}
          color="text-auto-ai"
          loading={summaryLoading}
        />
        <KpiCard
          title="Manual Rewrite"
          value={manualCount}
          subtitle="classes"
          icon={Wrench}
          color="text-auto-manual"
          loading={summaryLoading}
        />
        <KpiCard
          title="No Recipe"
          value={gaps?.length ?? "\u2014"}
          subtitle="unmapped types"
          icon={HelpCircle}
          color="text-muted-foreground"
          loading={gapsLoading}
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        {/* Actions by Category */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">Actions by Category</CardTitle>
          </CardHeader>
          <CardContent>
            {recipesLoading ? (
              <Skeleton className="h-[300px]" />
            ) : (
              <div className="space-y-1">
                {Object.entries(recipesByCategory)
                  .sort(([, a], [, b]) => b.length - a.length)
                  .map(([category, rules]) => (
                    <Collapsible key={category}>
                      <CollapsibleTrigger className="flex w-full items-center justify-between rounded px-2 py-1.5 text-sm hover:bg-accent">
                        <span>
                          {category}{" "}
                          <span className="text-muted-foreground">
                            ({rules.length} rules)
                          </span>
                        </span>
                      </CollapsibleTrigger>
                      <CollapsibleContent>
                        <div className="ml-4 space-y-1 border-l border-border pl-3">
                          {rules.slice(0, 10).map((r) => (
                            <div
                              key={r.id}
                              className="flex items-center justify-between py-0.5 text-xs"
                            >
                              <span className="text-muted-foreground">
                                {r.source.split(".").pop()} &rarr;{" "}
                                {r.target?.split(".").pop() ?? "\u2014"}
                              </span>
                              <AutomationBadge automatable={r.automatable} />
                            </div>
                          ))}
                          {rules.length > 10 && (
                            <p className="text-xs text-muted-foreground">
                              +{rules.length - 10} more
                            </p>
                          )}
                        </div>
                      </CollapsibleContent>
                    </Collapsible>
                  ))}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Unmapped Types */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">Unmapped Types</CardTitle>
          </CardHeader>
          <CardContent>
            {gapsLoading ? (
              <Skeleton className="h-[300px]" />
            ) : gaps && gaps.length > 0 ? (
              <div className="max-h-[300px] overflow-y-auto">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-card">
                    <tr className="border-b border-border text-left text-xs text-muted-foreground">
                      <th className="pb-2 pr-4">Source Type</th>
                      <th className="pb-2 text-right">Usages</th>
                    </tr>
                  </thead>
                  <tbody>
                    {gaps.map((g) => (
                      <tr
                        key={g.id}
                        className="border-b border-border/50"
                      >
                        <td className="py-1 pr-4 font-mono text-xs">
                          {g.source}
                        </td>
                        <td className="py-1 text-right font-mono text-xs">
                          {g.usageCount}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="py-12 text-center text-sm text-muted-foreground">
                All types are mapped
              </p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Classes Needing Manual Attention */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">
            Classes Needing Manual Attention
          </CardTitle>
        </CardHeader>
        <CardContent>
          {summaryLoading ? (
            <Skeleton className="h-[200px]" />
          ) : summary && summary.topGaps.length > 0 ? (
            <div className="max-h-[300px] overflow-y-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-card">
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="pb-2 pr-4">Class</th>
                    <th className="pb-2 text-right">Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.topGaps.map((gap) => (
                    <tr key={gap} className="border-b border-border/50">
                      <td className="py-1 pr-4 font-mono text-xs">{gap}</td>
                      <td className="py-1 text-right text-xs text-muted-foreground">
                        No matching recipe
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-muted-foreground">
              All classes have migration recipes
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
