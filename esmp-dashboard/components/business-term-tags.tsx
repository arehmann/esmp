"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import {
  Collapsible,
  CollapsibleTrigger,
  CollapsibleContent,
} from "@/components/ui/collapsible";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { BusinessTermResponse } from "@/lib/types";

// ── helpers ──────────────────────────────────────────────────────────────────

function criticalityColor(crit: string) {
  switch (crit?.toUpperCase()) {
    case "HIGH":
      return "border-amber-500 text-amber-500";
    case "MEDIUM":
      return "border-blue-500 text-blue-500";
    default:
      return "border-muted-foreground text-muted-foreground";
  }
}

type UiRoleStyle = { bg: string; text: string };

const UI_ROLE_STYLES: Record<string, UiRoleStyle> = {
  LABEL:        { bg: "bg-blue-500/10",   text: "text-blue-500" },
  MESSAGE:      { bg: "bg-amber-500/10",  text: "text-amber-500" },
  BUTTON:       { bg: "bg-green-500/10",  text: "text-green-500" },
  TOOLTIP:      { bg: "bg-purple-500/10", text: "text-purple-500" },
  ERROR:        { bg: "bg-red-500/10",    text: "text-red-500" },
  WARNING:      { bg: "bg-orange-500/10", text: "text-orange-500" },
  TITLE:        { bg: "bg-slate-500/10",  text: "text-slate-400" },
  ENUM_DISPLAY: { bg: "bg-cyan-500/10",   text: "text-cyan-500" },
};

function uiRoleStyle(role: string | null): UiRoleStyle {
  if (!role) return { bg: "bg-muted", text: "text-muted-foreground" };
  return (
    UI_ROLE_STYLES[role.toUpperCase()] ?? {
      bg: "bg-muted",
      text: "text-muted-foreground",
    }
  );
}

function sortTerms(terms: BusinessTermResponse[]): BusinessTermResponse[] {
  return [...terms].sort((a, b) => {
    const aNls = a.sourceType?.startsWith("NLS_") ? 0 : 1;
    const bNls = b.sourceType?.startsWith("NLS_") ? 0 : 1;
    if (aNls !== bNls) return aNls - bNls;
    return (b.usageCount ?? 0) - (a.usageCount ?? 0);
  });
}

// ── sub-components ────────────────────────────────────────────────────────────

function TermRow({ term }: { term: BusinessTermResponse }) {
  const [open, setOpen] = useState(false);
  const hasDoc = term.documentContext != null;
  const role = uiRoleStyle(term.uiRole);

  return (
    <div className="rounded-md border border-border/50 px-3 py-2 text-xs">
      <div className="flex items-start justify-between gap-2">
        {/* Left: name + definition */}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
            <span className="font-semibold text-sm leading-snug">
              {term.displayName}
            </span>
            {term.definition && (
              <span className="truncate text-xs text-muted-foreground">
                {term.definition}
              </span>
            )}
          </div>
          {term.sourceType?.startsWith("NLS_") && term.nlsFileName && (
            <span className="mt-0.5 block text-[10px] text-muted-foreground/60">
              {term.nlsFileName}
            </span>
          )}
        </div>

        {/* Right: uiRole badge + chevron */}
        <div className="flex shrink-0 items-center gap-1.5">
          {term.uiRole && (
            <span
              className={cn(
                "rounded px-1.5 py-0.5 text-[10px] font-medium",
                role.bg,
                role.text,
              )}
            >
              {term.uiRole}
            </span>
          )}
          {hasDoc && (
            <button
              type="button"
              aria-label={open ? "Collapse doc" : "Expand doc"}
              className="text-muted-foreground hover:text-foreground"
              onClick={() => setOpen((v) => !v)}
            >
              {open ? (
                <ChevronDown className="h-3.5 w-3.5" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5" />
              )}
            </button>
          )}
        </div>
      </div>

      {/* Collapsible doc context */}
      {hasDoc && (
        <Collapsible open={open} onOpenChange={setOpen}>
          <CollapsibleContent>
            <div className="mt-2 rounded bg-muted/40 p-2 text-[11px] leading-relaxed text-muted-foreground">
              <p>{term.documentContext}</p>
              {term.documentSource && (
                <p className="mt-1 text-[10px] opacity-70">
                  Source: {term.documentSource}
                </p>
              )}
            </div>
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  );
}

// ── compact badge view (legacy) ───────────────────────────────────────────────

function CompactView({ terms }: { terms: BusinessTermResponse[] }) {
  return (
    <TooltipProvider>
      <div className="flex flex-wrap gap-1">
        {terms.map((t) => (
          <Tooltip key={t.termId}>
            <TooltipTrigger>
              <Badge
                variant="outline"
                className={cn("text-xs", criticalityColor(t.criticality))}
              >
                {t.displayName}
              </Badge>
            </TooltipTrigger>
            <TooltipContent>
              <p className="max-w-xs text-xs">{t.definition || "No definition"}</p>
              <p className="mt-1 text-[10px] text-muted-foreground">
                Criticality: {t.criticality} | Usages: {t.usageCount}
              </p>
            </TooltipContent>
          </Tooltip>
        ))}
      </div>
    </TooltipProvider>
  );
}

// ── main export ───────────────────────────────────────────────────────────────

export function BusinessTermTags({
  terms,
  compact = false,
}: {
  terms: BusinessTermResponse[];
  compact?: boolean;
}) {
  if (terms.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">No business terms linked</p>
    );
  }

  if (compact) {
    return <CompactView terms={terms} />;
  }

  const sorted = sortTerms(terms);

  return (
    <div className="max-h-[400px] overflow-y-auto space-y-1.5 pr-1">
      {sorted.map((term) => (
        <TermRow key={term.termId} term={term} />
      ))}
    </div>
  );
}
