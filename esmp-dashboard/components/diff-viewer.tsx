"use client";

import { cn } from "@/lib/utils";

export function DiffViewer({ diff }: { diff: string | null }) {
  if (!diff) {
    return (
      <div className="flex h-32 items-center justify-center rounded border border-border bg-slate-950 text-xs text-muted-foreground">
        Click &quot;Preview Diff&quot; to generate a diff preview
      </div>
    );
  }

  const lines = diff.split("\n");

  return (
    <div className="max-h-[400px] overflow-auto rounded border border-border bg-slate-950 font-mono text-xs">
      {lines.map((line, i) => (
        <div
          key={i}
          className={cn(
            "px-3 py-0.5",
            line.startsWith("+") && !line.startsWith("+++") && "bg-green-900/30 text-green-400",
            line.startsWith("-") && !line.startsWith("---") && "bg-red-900/30 text-red-400",
            line.startsWith("@@") && "text-blue-400",
            !line.startsWith("+") &&
              !line.startsWith("-") &&
              !line.startsWith("@@") &&
              "text-muted-foreground"
          )}
        >
          {line || " "}
        </div>
      ))}
    </div>
  );
}
