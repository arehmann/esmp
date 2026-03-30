"use client";

import { AutomationBadge } from "./automation-badge";
import type { MigrationActionEntry } from "@/lib/types";
import { cn } from "@/lib/utils";

interface MigrationActionListProps {
  actions: MigrationActionEntry[];
  onSelect: (action: MigrationActionEntry) => void;
  selectedSource?: string;
}

export function MigrationActionList({
  actions,
  onSelect,
  selectedSource,
}: MigrationActionListProps) {
  // Sort: manual first, then AI, then auto
  const sorted = [...actions].sort((a, b) => {
    const order = (v: string) =>
      v === "NO" ? 0 : v === "YES_AI" || v === "PARTIAL" ? 1 : 2;
    return order(a.automatable) - order(b.automatable);
  });

  return (
    <div className="space-y-1">
      {sorted.map((action, i) => {
        const icon =
          action.automatable === "NO"
            ? "\u2717"
            : action.automatable === "YES_AI" || action.automatable === "PARTIAL"
              ? "\u26A1"
              : "\u2713";
        return (
          <button
            key={`${action.source}-${i}`}
            onClick={() => onSelect(action)}
            className={cn(
              "flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-accent",
              selectedSource === action.source && "bg-accent"
            )}
          >
            <span className="w-4 text-center">{icon}</span>
            <span className="flex-1 truncate font-mono">
              {action.source.split(".").pop()} →{" "}
              {action.target?.split(".").pop() ?? "manual"}
            </span>
            <AutomationBadge automatable={action.automatable} />
          </button>
        );
      })}
      {sorted.length === 0 && (
        <p className="py-4 text-center text-xs text-muted-foreground">
          No migration actions detected
        </p>
      )}
    </div>
  );
}
