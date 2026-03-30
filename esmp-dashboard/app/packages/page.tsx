"use client";

import { Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { RiskBadge } from "@/components/risk-badge";
import { StereotypeBadges } from "@/components/stereotype-badges";
import { PackageTreemap } from "@/components/package-treemap";
import { useHeatmap } from "@/lib/queries";
import { groupByPackage } from "@/lib/utils";

function PackagesContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const selectedPkg = searchParams.get("package");
  const { data: heatmap, isLoading } = useHeatmap();

  const packages = heatmap
    ? groupByPackage(heatmap).sort((a, b) => b.classCount - a.classCount)
    : [];

  const filteredClasses = selectedPkg
    ? heatmap?.filter((c) => c.packageName === selectedPkg) ?? []
    : heatmap ?? [];

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold">Packages</h2>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">
            Package Treemap{" "}
            <span className="font-normal text-muted-foreground">
              (size = class count, color = avg risk)
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-[350px]" />
          ) : (
            <PackageTreemap
              packages={packages}
              onSelectPackage={(pkg) =>
                router.push(`/packages?package=${encodeURIComponent(pkg)}`)
              }
            />
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center justify-between text-sm">
            <span>
              Classes{" "}
              {selectedPkg && (
                <span className="font-normal text-muted-foreground">
                  in {selectedPkg}
                </span>
              )}
            </span>
            {selectedPkg && (
              <button
                onClick={() => router.push("/packages")}
                className="text-xs text-primary hover:underline"
              >
                Clear filter
              </button>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-[400px]" />
          ) : (
            <div className="max-h-[500px] overflow-y-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-card">
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="pb-2 pr-4">Name</th>
                    <th className="pb-2 pr-4">Stereotype</th>
                    <th className="pb-2 pr-4 text-right">CC Max</th>
                    <th className="pb-2 pr-4 text-right">Fan-In</th>
                    <th className="pb-2 pr-4 text-right">Fan-Out</th>
                    <th className="pb-2 text-right">Risk</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredClasses
                    .sort(
                      (a, b) => b.enhancedRiskScore - a.enhancedRiskScore
                    )
                    .map((c) => (
                      <tr
                        key={c.fqn}
                        className="border-b border-border/50 hover:bg-accent/50"
                      >
                        <td className="py-1.5 pr-4">
                          <Link
                            href={`/class/${c.fqn}`}
                            className="text-primary hover:underline"
                          >
                            {c.simpleName}
                          </Link>
                        </td>
                        <td className="py-1.5 pr-4">
                          <StereotypeBadges labels={c.stereotypeLabels} />
                        </td>
                        <td className="py-1.5 pr-4 text-right font-mono text-xs">
                          {c.complexityMax}
                        </td>
                        <td className="py-1.5 pr-4 text-right font-mono text-xs">
                          {c.fanIn}
                        </td>
                        <td className="py-1.5 pr-4 text-right font-mono text-xs">
                          {c.fanOut}
                        </td>
                        <td className="py-1.5 text-right">
                          <RiskBadge score={c.enhancedRiskScore} />
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

export default function PackagesPage() {
  return (
    <Suspense fallback={<Skeleton className="h-[600px]" />}>
      <PackagesContent />
    </Suspense>
  );
}
