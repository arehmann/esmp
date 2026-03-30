"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Badge } from "@/components/ui/badge";
import { useGraphSearch } from "@/lib/queries";

export function SearchCommand({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const { data: results } = useGraphSearch(query);

  // Ctrl+K global shortcut
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        onOpenChange(!open);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onOpenChange]);

  function handleSelect(fqn: string) {
    onOpenChange(false);
    setQuery("");
    router.push(`/class/${fqn}`);
  }

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange}>
      <CommandInput
        placeholder="Search classes..."
        value={query}
        onValueChange={setQuery}
      />
      <CommandList>
        <CommandEmpty>
          {query.length < 2
            ? "Type at least 2 characters to search"
            : `No classes matching "${query}"`}
        </CommandEmpty>
        {results && results.results.length > 0 && (
          <CommandGroup heading="Classes">
            {results.results.slice(0, 20).map((r) => (
              <CommandItem
                key={r.fullyQualifiedName}
                value={r.fullyQualifiedName}
                onSelect={() => handleSelect(r.fullyQualifiedName)}
              >
                <div className="flex flex-1 items-center justify-between">
                  <div>
                    <span className="font-medium">{r.simpleName}</span>
                    <span className="ml-2 text-xs text-muted-foreground">
                      {r.packageName}
                    </span>
                  </div>
                  <div className="flex gap-1">
                    {r.labels.slice(0, 3).map((l) => (
                      <Badge key={l} variant="secondary" className="text-[10px]">
                        {l.replace("Java", "")}
                      </Badge>
                    ))}
                  </div>
                </div>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
      </CommandList>
    </CommandDialog>
  );
}
