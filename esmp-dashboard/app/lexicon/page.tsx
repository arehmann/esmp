"use client";

import { useMemo, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { useLexicon } from "@/lib/queries";

const SOURCE_TYPES = [
  "All",
  "CLASS_NAME",
  "NLS_LABEL",
  "NLS_MESSAGE",
  "NLS_TYPE",
  "ENUM_CONSTANT",
] as const;

type SortKey = "termId" | "displayName" | "sourceType" | "criticality" | "usageCount";

function criticalityColor(c: string) {
  switch (c) {
    case "High":
      return "destructive";
    case "Medium":
      return "secondary";
    default:
      return "outline";
  }
}

export default function LexiconPage() {
  const [activeSource, setActiveSource] = useState<string>("All");
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("usageCount");
  const [sortAsc, setSortAsc] = useState(false);

  const sourceParam = activeSource === "All" ? undefined : activeSource;
  const searchParam = search.length >= 2 ? search : undefined;

  const { data: terms, isLoading } = useLexicon(sourceParam, undefined, searchParam);

  const sorted = useMemo(() => {
    if (!terms) return [];
    return [...terms].sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (typeof av === "string" && typeof bv === "string")
        return sortAsc ? av.localeCompare(bv) : bv.localeCompare(av);
      return sortAsc
        ? (av as number) - (bv as number)
        : (bv as number) - (av as number);
    });
  }, [terms, sortKey, sortAsc]);

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortAsc(!sortAsc);
    else {
      setSortKey(key);
      setSortAsc(false);
    }
  }

  const COLUMNS: { key: SortKey; label: string; align?: "right" }[] = [
    { key: "termId", label: "Term ID" },
    { key: "displayName", label: "Display Name" },
    { key: "sourceType", label: "Source Type" },
    { key: "criticality", label: "Criticality" },
    { key: "usageCount", label: "Usage", align: "right" },
  ];

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">Business Lexicon</h2>

      {/* Source type tabs */}
      <div className="flex flex-wrap items-center gap-2">
        {SOURCE_TYPES.map((st) => (
          <button
            key={st}
            onClick={() => setActiveSource(st)}
            className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              activeSource === st
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:bg-accent hover:text-foreground"
            }`}
          >
            {st === "All" ? "All" : st.replace(/_/g, " ")}
          </button>
        ))}

        <input
          type="text"
          placeholder="Search terms..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="ml-auto rounded-md border border-border bg-background px-3 py-1.5 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
        />

        <span className="text-xs text-muted-foreground">
          {sorted.length} terms
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
                    <th className="px-3 pb-2 pt-3">Definition</th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((t) => (
                    <tr
                      key={t.termId}
                      className="border-b border-border/50 hover:bg-accent/50"
                    >
                      <td className="px-3 py-1.5 font-mono text-xs">
                        {t.termId}
                      </td>
                      <td className="px-3 py-1.5 font-medium">
                        {t.displayName}
                      </td>
                      <td className="px-3 py-1.5 text-xs text-muted-foreground">
                        {t.sourceType}
                      </td>
                      <td className="px-3 py-1.5">
                        <Badge variant={criticalityColor(t.criticality)}>
                          {t.criticality}
                        </Badge>
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono text-xs">
                        {t.usageCount}
                      </td>
                      <td className="max-w-[300px] truncate px-3 py-1.5 text-xs text-muted-foreground">
                        {t.definition || "-"}
                      </td>
                    </tr>
                  ))}
                  {sorted.length === 0 && (
                    <tr>
                      <td colSpan={6} className="px-3 py-8 text-center text-sm text-muted-foreground">
                        No terms found.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
