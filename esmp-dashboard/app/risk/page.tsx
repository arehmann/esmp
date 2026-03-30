"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { RiskBadge } from "@/components/risk-badge";
import { StereotypeBadges } from "@/components/stereotype-badges";
import { useHeatmap } from "@/lib/queries";
import type { RiskHeatmapEntry } from "@/lib/types";

type SortKey = keyof Pick<
  RiskHeatmapEntry,
  | "simpleName"
  | "complexitySum"
  | "complexityMax"
  | "fanIn"
  | "fanOut"
  | "dbWriteCount"
  | "structuralRiskScore"
  | "enhancedRiskScore"
>;

const COLUMNS: { key: SortKey; label: string; align?: "right" }[] = [
  { key: "simpleName", label: "Name" },
  { key: "complexitySum", label: "CC Sum", align: "right" },
  { key: "complexityMax", label: "CC Max", align: "right" },
  { key: "fanIn", label: "Fan-In", align: "right" },
  { key: "fanOut", label: "Fan-Out", align: "right" },
  { key: "dbWriteCount", label: "DB Writes", align: "right" },
  { key: "structuralRiskScore", label: "Structural", align: "right" },
  { key: "enhancedRiskScore", label: "Enhanced", align: "right" },
];

export default function RiskPage() {
  const { data: heatmap, isLoading } = useHeatmap();
  const [sortKey, setSortKey] = useState<SortKey>("enhancedRiskScore");
  const [sortAsc, setSortAsc] = useState(false);
  const [filterPkg, setFilterPkg] = useState<string>("all");
  const [filterStereo, setFilterStereo] = useState<string>("all");
  const [minRisk, setMinRisk] = useState(0);

  const allPackages = useMemo(() => {
    if (!heatmap) return [];
    return [...new Set(heatmap.map((c) => c.packageName))].sort();
  }, [heatmap]);

  const allStereotypes = useMemo(() => {
    if (!heatmap) return [];
    return [...new Set(heatmap.flatMap((c) => c.stereotypeLabels))].sort();
  }, [heatmap]);

  const filtered = useMemo(() => {
    if (!heatmap) return [];
    return heatmap
      .filter((c) => filterPkg === "all" || c.packageName === filterPkg)
      .filter(
        (c) =>
          filterStereo === "all" || c.stereotypeLabels.includes(filterStereo)
      )
      .filter((c) => c.enhancedRiskScore >= minRisk)
      .sort((a, b) => {
        const av = a[sortKey];
        const bv = b[sortKey];
        if (typeof av === "string" && typeof bv === "string")
          return sortAsc ? av.localeCompare(bv) : bv.localeCompare(av);
        return sortAsc
          ? (av as number) - (bv as number)
          : (bv as number) - (av as number);
      });
  }, [heatmap, sortKey, sortAsc, filterPkg, filterStereo, minRisk]);

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortAsc(!sortAsc);
    else {
      setSortKey(key);
      setSortAsc(false);
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">Risk Heatmap</h2>

      {/* Filters */}
      <div className="flex items-center gap-4">
        <Select value={filterPkg} onValueChange={(v) => setFilterPkg(v ?? "all")}>
          <SelectTrigger className="w-[250px]">
            <SelectValue placeholder="All Packages" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Packages</SelectItem>
            {allPackages.map((p) => (
              <SelectItem key={p} value={p}>
                {p}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={filterStereo} onValueChange={(v) => setFilterStereo(v ?? "all")}>
          <SelectTrigger className="w-[180px]">
            <SelectValue placeholder="All Stereotypes" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Stereotypes</SelectItem>
            {allStereotypes.map((s) => (
              <SelectItem key={s} value={s}>
                {s}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="flex items-center gap-2 text-sm">
          <label className="text-muted-foreground">Min Risk:</label>
          <input
            type="range"
            min={0}
            max={5}
            step={0.1}
            value={minRisk}
            onChange={(e) => setMinRisk(Number(e.target.value))}
            className="w-24"
          />
          <span className="w-8 font-mono text-xs">{minRisk.toFixed(1)}</span>
        </div>

        <span className="ml-auto text-xs text-muted-foreground">
          {filtered.length} classes
        </span>
      </div>

      {/* Data table */}
      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="p-4">
              <Skeleton className="h-[500px]" />
            </div>
          ) : (
            <div className="max-h-[calc(100vh-220px)] overflow-y-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-card">
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    {COLUMNS.map((col) => (
                      <th
                        key={col.key}
                        className={`cursor-pointer px-3 pb-2 pt-3 hover:text-foreground ${
                          col.align === "right" ? "text-right" : ""
                        }`}
                        onClick={() => toggleSort(col.key)}
                      >
                        {col.label}
                        {sortKey === col.key && (sortAsc ? " \u2191" : " \u2193")}
                      </th>
                    ))}
                    <th className="px-3 pb-2 pt-3">Stereotype</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((c) => (
                    <tr
                      key={c.fqn}
                      className="border-b border-border/50 hover:bg-accent/50"
                    >
                      <td className="px-3 py-1.5">
                        <Link
                          href={`/class/${c.fqn}`}
                          className="text-primary hover:underline"
                          title={c.fqn}
                        >
                          {c.simpleName}
                        </Link>
                        <div className="text-[10px] text-muted-foreground">
                          {c.packageName}
                        </div>
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.complexitySum}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.complexityMax}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.fanIn}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.fanOut}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.dbWriteCount}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {c.structuralRiskScore.toFixed(2)}
                      </td>
                      <td className="px-3 py-1.5 text-right">
                        <RiskBadge score={c.enhancedRiskScore} />
                      </td>
                      <td className="px-3 py-1.5">
                        <StereotypeBadges labels={c.stereotypeLabels} />
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
