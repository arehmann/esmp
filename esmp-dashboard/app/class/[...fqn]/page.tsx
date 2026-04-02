"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";
import { RiskBadge } from "@/components/risk-badge";
import { StereotypeBadges } from "@/components/stereotype-badges";
import { KpiCard } from "@/components/kpi-card";
import { MigrationActionList } from "@/components/migration-action-list";
import { DiffViewer } from "@/components/diff-viewer";
import { BusinessTermTags } from "@/components/business-term-tags";
import { DependencyList } from "@/components/dependency-graph";
import {
  useMigrationPlan,
  useMigrationPreview,
  useRiskDetail,
  useDependencyCone,
  useTermsByClass,
  useHeatmap,
} from "@/lib/queries";
import { AlertTriangle, Layers, Zap, Activity, Clipboard, ClipboardCheck } from "lucide-react";
import { formatPercent, simpleNameFromFqn } from "@/lib/utils";
import type { MigrationActionEntry, RiskHeatmapEntry } from "@/lib/types";

export default function ClassDetailPage() {
  const params = useParams<{ fqn: string[] }>();
  const fqn = (params.fqn as string[]).join(".");

  const { data: plan, isLoading: planLoading } = useMigrationPlan(fqn);
  const { data: risk, isLoading: riskLoading } = useRiskDetail(fqn);
  const { data: cone, isLoading: coneLoading } = useDependencyCone(fqn);
  const { data: terms, isLoading: termsLoading } = useTermsByClass(fqn);
  const { data: heatmap } = useHeatmap();
  const preview = useMigrationPreview(fqn);

  const [selectedAction, setSelectedAction] =
    useState<MigrationActionEntry | null>(null);
  const [copied, setCopied] = useState(false);

  // Build risk lookup map from cached heatmap for dependency risk coloring
  const riskMap = useMemo(() => {
    const map = new Map<string, RiskHeatmapEntry>();
    heatmap?.forEach((entry) => map.set(entry.fqn, entry));
    return map;
  }, [heatmap]);

  const allActions = plan
    ? [...plan.manualActions, ...plan.automatableActions]
    : [];

  const simpleName = simpleNameFromFqn(fqn);
  const packageName = fqn.substring(0, fqn.lastIndexOf("."));

  // Business context derived values
  const nlsTermCount = terms?.filter((t) => t.sourceType?.startsWith("NLS_")).length ?? 0;
  const domainArea = terms?.find((t) => t.domainArea)?.domainArea ?? "Unknown";

  function handleCopyMcpPrompt() {
    const prompt = `Use getMigrationContext("${fqn}") to understand this class, then plan the Vaadin 7 to 24 migration.`;
    navigator.clipboard.writeText(prompt).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  // Breadcrumb segments
  const breadcrumbs = [
    { label: "adsuite-market", href: "/" },
    {
      label: packageName.split(".").slice(-2).join("."),
      href: `/packages?package=${encodeURIComponent(packageName)}`,
    },
    { label: simpleName, href: null },
  ];

  return (
    <div className="space-y-4">
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1 text-xs text-muted-foreground">
        {breadcrumbs.map((b, i) => (
          <span key={i} className="flex items-center gap-1">
            {i > 0 && <span>›</span>}
            {b.href ? (
              <Link href={b.href} className="hover:text-foreground">
                {b.label}
              </Link>
            ) : (
              <span className="text-foreground">{b.label}</span>
            )}
          </span>
        ))}
      </nav>

      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-xl font-bold">{simpleName}</h2>
          <p className="mt-0.5 font-mono text-xs text-muted-foreground">{fqn}</p>
          <div className="mt-1 flex items-center gap-2">
            {risk && <StereotypeBadges labels={risk.stereotypeLabels} />}
            {risk && <RiskBadge score={risk.enhancedRiskScore} />}
          </div>
        </div>
        <Button
          size="sm"
          variant="outline"
          className="h-7 gap-1.5 text-xs"
          onClick={handleCopyMcpPrompt}
        >
          {copied ? (
            <>
              <ClipboardCheck className="h-3.5 w-3.5 text-green-500" />
              Copied!
            </>
          ) : (
            <>
              <Clipboard className="h-3.5 w-3.5" />
              Copy MCP Prompt
            </>
          )}
        </Button>
      </div>

      {/* Metric cards */}
      <div className="grid grid-cols-4 gap-3">
        <KpiCard
          title="Risk Score"
          value={risk?.enhancedRiskScore.toFixed(2) ?? "—"}
          icon={AlertTriangle}
          loading={riskLoading}
        />
        <KpiCard
          title="Complexity"
          value={risk?.complexitySum.toLocaleString() ?? "—"}
          subtitle={risk ? `Max CC: ${risk.complexityMax}` : undefined}
          icon={Activity}
          loading={riskLoading}
        />
        <KpiCard
          title="Actions"
          value={plan?.totalActions ?? "—"}
          subtitle={
            plan ? `${plan.automatableCount} auto, ${plan.manualCount} manual` : undefined
          }
          icon={Layers}
          loading={planLoading}
        />
        <KpiCard
          title="Automation"
          value={plan ? formatPercent(plan.automationScore) : "—"}
          icon={Zap}
          color={
            plan
              ? plan.automationScore > 0.7
                ? "text-auto-full"
                : plan.automationScore > 0.3
                  ? "text-auto-ai"
                  : "text-auto-manual"
              : undefined
          }
          loading={planLoading}
        />
      </div>

      {/* Main content: left (actions + diff) | right (context) */}
      <div className="grid grid-cols-3 gap-4">
        {/* Left column: 2/3 */}
        <div className="col-span-2 space-y-4">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">Migration Actions</CardTitle>
            </CardHeader>
            <CardContent>
              {planLoading ? (
                <Skeleton className="h-[200px]" />
              ) : (
                <MigrationActionList
                  actions={allActions}
                  onSelect={setSelectedAction}
                  selectedSource={selectedAction?.source}
                />
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <CardTitle className="text-sm">Diff Preview</CardTitle>
              <Button
                size="sm"
                variant="outline"
                className="h-7 text-xs"
                onClick={() => preview.mutate()}
                disabled={preview.isPending}
              >
                {preview.isPending ? "Generating..." : "Preview Diff"}
              </Button>
            </CardHeader>
            <CardContent>
              {preview.isError && (
                <p className="mb-2 text-xs text-red-400">
                  Preview failed: {(preview.error as Error).message}
                </p>
              )}
              <DiffViewer diff={preview.data?.diff ?? null} />
            </CardContent>
          </Card>
        </div>

        {/* Right column: 1/3 */}
        <div className="space-y-4">
          {/* Business Context card */}
          <Card>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm">Business Context</CardTitle>
                {!termsLoading && (
                  <Badge variant="outline" className="text-[10px]">
                    {domainArea}
                  </Badge>
                )}
              </div>
            </CardHeader>
            <CardContent>
              {riskLoading || termsLoading ? (
                <Skeleton className="h-[60px]" />
              ) : (
                <div className="space-y-2 text-xs">
                  {risk?.curatedClassDescription ? (
                    <p className="leading-relaxed">
                      {risk.curatedClassDescription}
                    </p>
                  ) : risk?.businessDescription ? (
                    <p className="leading-relaxed text-muted-foreground">
                      {risk.businessDescription}
                    </p>
                  ) : (
                    <p className="text-muted-foreground italic">
                      No business description available.
                    </p>
                  )}
                  <p className="text-[11px] text-muted-foreground/70">
                    {nlsTermCount} NLS term{nlsTermCount !== 1 ? "s" : ""} linked
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">Business Terms</CardTitle>
            </CardHeader>
            <CardContent>
              {termsLoading ? (
                <Skeleton className="h-[60px]" />
              ) : (
                <BusinessTermTags terms={terms ?? []} />
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">
                Dependencies{" "}
                {cone && (
                  <span className="font-normal text-muted-foreground">
                    ({cone.coneSize})
                  </span>
                )}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {coneLoading ? (
                <Skeleton className="h-[200px]" />
              ) : (
                <DependencyList
                  coneNodes={cone?.coneNodes ?? []}
                  riskMap={riskMap}
                />
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">Risk Breakdown</CardTitle>
            </CardHeader>
            <CardContent>
              {riskLoading ? (
                <Skeleton className="h-[100px]" />
              ) : risk ? (
                <div className="space-y-1 text-xs">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Max CC</span>
                    <span className="font-mono">{risk.complexityMax}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">DB Writes</span>
                    <span className="font-mono">{risk.dbWriteCount}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Fan-In</span>
                    <span className="font-mono">{risk.fanIn}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Fan-Out</span>
                    <span className="font-mono">{risk.fanOut}</span>
                  </div>
                  <Separator className="my-1" />
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      Domain Criticality
                    </span>
                    <span className="font-mono">
                      {risk.domainCriticality.toFixed(2)}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      Security Sensitivity
                    </span>
                    <span className="font-mono">
                      {risk.securitySensitivity.toFixed(2)}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Financial</span>
                    <span className="font-mono">
                      {risk.financialInvolvement.toFixed(2)}
                    </span>
                  </div>
                </div>
              ) : (
                <p className="text-xs text-muted-foreground">
                  Risk data unavailable
                </p>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
