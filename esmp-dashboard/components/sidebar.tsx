"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  BarChart3, Package, AlertTriangle, RefreshCw, BookOpen,
  ChevronLeft, ChevronRight, Search,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { useHeatmap } from "@/lib/queries";
import { groupByPackage, cn } from "@/lib/utils";

const NAV_ITEMS = [
  { href: "/", label: "Overview", icon: BarChart3 },
  { href: "/packages", label: "Packages", icon: Package },
  { href: "/risk", label: "Risk Heatmap", icon: AlertTriangle },
  { href: "/migration", label: "Migration", icon: RefreshCw },
  { href: "/recipes", label: "Recipes", icon: BookOpen },
] as const;

export function Sidebar({ onSearchOpen }: { onSearchOpen: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const [collapsed, setCollapsed] = useState(false);
  const [pkgOpen, setPkgOpen] = useState(true);
  const { data: heatmap } = useHeatmap();

  const packages = heatmap
    ? groupByPackage(heatmap).sort((a, b) => b.classCount - a.classCount)
    : [];

  return (
    <aside
      className={cn(
        "flex flex-col border-r border-border bg-card transition-all duration-150",
        collapsed ? "w-12" : "w-64"
      )}
    >
      <div className="flex items-center justify-between px-3 py-4">
        {!collapsed && (
          <div>
            <h1 className="text-sm font-bold text-primary">ESMP</h1>
            <p className="text-xs text-muted-foreground">adsuite-market</p>
          </div>
        )}
        <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => setCollapsed(!collapsed)}>
          {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
        </Button>
      </div>

      <Separator />

      <ScrollArea className="flex-1">
        <nav className="px-2 py-2">
          {!collapsed && (
            <p className="px-2 pb-1 text-xs font-medium text-muted-foreground">NAVIGATION</p>
          )}
          {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
            const active = pathname === href;
            return (
              <Link key={href} href={href}
                className={cn(
                  "flex items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors",
                  active ? "bg-primary/10 text-primary" : "text-muted-foreground hover:bg-accent hover:text-foreground"
                )}
              >
                <Icon className="h-4 w-4 shrink-0" />
                {!collapsed && label}
              </Link>
            );
          })}
        </nav>

        {!collapsed && packages.length > 0 && (
          <>
            <Separator className="my-2" />
            <Collapsible open={pkgOpen} onOpenChange={setPkgOpen}>
              <CollapsibleTrigger className="flex w-full items-center justify-between px-4 py-1 text-xs font-medium text-muted-foreground hover:text-foreground">
                PACKAGES
                <ChevronRight className={cn("h-3 w-3 transition-transform", pkgOpen && "rotate-90")} />
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="px-2 py-1">
                  {packages.slice(0, 30).map((pkg) => (
                    <button key={pkg.packageName}
                      onClick={() => router.push(`/packages?package=${encodeURIComponent(pkg.packageName)}`)}
                      className="flex w-full items-center justify-between rounded px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
                    >
                      <span className="truncate">{pkg.shortName}</span>
                      <span className="ml-1 text-xs opacity-60">({pkg.classCount})</span>
                    </button>
                  ))}
                </div>
              </CollapsibleContent>
            </Collapsible>
          </>
        )}
      </ScrollArea>

      <Separator />
      <button onClick={onSearchOpen}
        className="flex items-center gap-2 px-3 py-3 text-xs text-muted-foreground hover:text-foreground"
      >
        <Search className="h-4 w-4" />
        {!collapsed && (
          <span>Search <kbd className="ml-1 rounded border border-border px-1 text-[10px]">Ctrl+K</kbd></span>
        )}
      </button>
    </aside>
  );
}
