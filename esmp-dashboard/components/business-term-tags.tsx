"use client";

import { Badge } from "@/components/ui/badge";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import type { BusinessTermResponse } from "@/lib/types";

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

export function BusinessTermTags({
  terms,
}: {
  terms: BusinessTermResponse[];
}) {
  if (terms.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">No business terms linked</p>
    );
  }

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
