"use client";

import { Fragment, useMemo, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AutomationBadge } from "@/components/automation-badge";
import { useRecipeBook, useRecipeGaps } from "@/lib/queries";
import { cn } from "@/lib/utils";
import type { RecipeRule } from "@/lib/types";

type TabValue = "all" | "gaps" | "discovered";

function statusColor(status: string) {
  switch (status) {
    case "MAPPED":
      return "border-green-500 text-green-500";
    case "NEEDS_MAPPING":
      return "border-amber-500 text-amber-500";
    case "DISCOVERED":
      return "border-blue-500 text-blue-500";
    default:
      return "border-muted-foreground text-muted-foreground";
  }
}

export default function RecipesPage() {
  const { data: recipes, isLoading: recipesLoading } = useRecipeBook();
  const { data: gaps, isLoading: gapsLoading } = useRecipeGaps();
  const [tab, setTab] = useState<TabValue>("all");
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    if (tab === "gaps") return gaps ?? [];
    if (tab === "discovered")
      return recipes?.filter((r) => r.status === "DISCOVERED") ?? [];
    return recipes ?? [];
  }, [tab, recipes, gaps]);

  const isLoading = recipesLoading || gapsLoading;

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">Recipes</h2>

      <Tabs
        value={tab}
        onValueChange={(v) => setTab(v as TabValue)}
      >
        <TabsList>
          <TabsTrigger value="all">
            All Rules{recipes ? ` (${recipes.length})` : ""}
          </TabsTrigger>
          <TabsTrigger value="gaps">
            Gaps Only{gaps ? ` (${gaps.length})` : ""}
          </TabsTrigger>
          <TabsTrigger value="discovered">Discovered</TabsTrigger>
        </TabsList>
      </Tabs>

      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="p-4">
              <Skeleton className="h-[500px]" />
            </div>
          ) : (
            <div className="max-h-[calc(100vh-200px)] overflow-y-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-card">
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="px-3 pb-2 pt-3">ID</th>
                    <th className="px-3 pb-2 pt-3">Source</th>
                    <th className="px-3 pb-2 pt-3">Target</th>
                    <th className="px-3 pb-2 pt-3">Category</th>
                    <th className="px-3 pb-2 pt-3">Automatable</th>
                    <th className="px-3 pb-2 pt-3">Status</th>
                    <th className="px-3 pb-2 pt-3 text-right">Usages</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((r) => (
                    <Fragment key={r.id}>
                      <tr
                        className="cursor-pointer border-b border-border/50 hover:bg-accent/50"
                        onClick={() =>
                          setExpandedId(expandedId === r.id ? null : r.id)
                        }
                      >
                        <td className="px-3 py-1.5 font-mono text-xs">
                          {r.id}
                        </td>
                        <td className="px-3 py-1.5 font-mono text-xs">
                          {r.source.split(".").pop()}
                        </td>
                        <td className="px-3 py-1.5 font-mono text-xs">
                          {r.target?.split(".").pop() ?? "\u2014"}
                        </td>
                        <td className="px-3 py-1.5">
                          <Badge variant="secondary" className="text-[10px]">
                            {r.category}
                          </Badge>
                        </td>
                        <td className="px-3 py-1.5">
                          <AutomationBadge automatable={r.automatable} />
                        </td>
                        <td className="px-3 py-1.5">
                          <Badge
                            variant="outline"
                            className={cn("text-[10px]", statusColor(r.status))}
                          >
                            {r.status}
                          </Badge>
                        </td>
                        <td className="px-3 py-1.5 text-right font-mono text-xs">
                          {r.usageCount}
                        </td>
                      </tr>
                      {expandedId === r.id && (
                        <tr>
                          <td
                            colSpan={7}
                            className="border-b border-border/50 bg-accent/30 px-6 py-3"
                          >
                            <div className="space-y-2">
                              <p className="text-xs text-muted-foreground">
                                <strong>Source:</strong> {r.source}
                              </p>
                              {r.target && (
                                <p className="text-xs text-muted-foreground">
                                  <strong>Target:</strong> {r.target}
                                </p>
                              )}
                              {r.context && (
                                <p className="text-xs text-muted-foreground">
                                  <strong>Context:</strong> {r.context}
                                </p>
                              )}
                              {r.migrationSteps.length > 0 && (
                                <div>
                                  <p className="text-xs font-medium">
                                    Migration Steps:
                                  </p>
                                  <ol className="ml-4 list-decimal text-xs text-muted-foreground">
                                    {r.migrationSteps.map((step, i) => (
                                      <li key={i}>{step}</li>
                                    ))}
                                  </ol>
                                </div>
                              )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
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
