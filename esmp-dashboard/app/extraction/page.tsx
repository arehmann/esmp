"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";
import {
  Play,
  RefreshCw,
  CheckCircle,
  Circle,
  Loader2,
  AlertCircle,
  Database,
  Clock,
} from "lucide-react";
import { cn } from "@/lib/utils";
import * as api from "@/lib/api";
import type { ExtractionProgressEvent } from "@/lib/types";

interface StageInfo {
  stage: string;
  module: string | null;
  status: "pending" | "active" | "done" | "error";
  filesProcessed: number;
  totalFiles: number;
  message: string | null;
  durationMs: number | null;
}

const STAGE_ORDER = [
  "SCANNING",
  "PARSING",
  "VISITING",
  "PERSISTING",
  "LINKING",
  "RISK_SCORING",
  "MIGRATION",
  "COMPLETE",
];

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const secs = seconds % 60;
  if (minutes < 60) return `${minutes}m ${secs}s`;
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return `${hours}h ${mins}m`;
}

export default function ExtractionPage() {
  const [jobId, setJobId] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [stages, setStages] = useState<StageInfo[]>([]);
  const [currentStage, setCurrentStage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [completed, setCompleted] = useState(false);
  const [startTime, setStartTime] = useState<number | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [sourceStatus, setSourceStatus] = useState<{
    sourceRoot: string;
    resolved: boolean;
    strategy: string;
  } | null>(null);
  const eventSourceRef = useRef<EventSource | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Fetch source status on mount
  useEffect(() => {
    api.getSourceStatus().then(setSourceStatus).catch(() => {});
  }, []);

  // Elapsed timer
  useEffect(() => {
    if (running && startTime) {
      timerRef.current = setInterval(() => {
        setElapsed(Date.now() - startTime);
      }, 1000);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [running, startTime]);

  const connectSSE = useCallback(
    (jid: string) => {
      const es = new EventSource(`/esmp-api/extraction/progress?jobId=${jid}`);
      eventSourceRef.current = es;

      es.addEventListener("progress", (e) => {
        const event: ExtractionProgressEvent = JSON.parse(e.data);
        setCurrentStage(event.stage);

        setStages((prev) => {
          const key = event.module
            ? `${event.stage}:${event.module}`
            : event.stage;
          const existing = prev.find(
            (s) =>
              s.stage === event.stage &&
              s.module === event.module
          );

          if (existing) {
            return prev.map((s) =>
              s.stage === event.stage && s.module === event.module
                ? {
                    ...s,
                    status:
                      event.stage === "COMPLETE" ||
                      event.stage === "EXTRACTION_COMPLETE"
                        ? "done"
                        : event.stage === "FAILED" || event.stage === "SKIPPED"
                          ? "error"
                          : "active",
                    filesProcessed: event.filesProcessed,
                    totalFiles: event.totalFiles,
                    message: event.message,
                    durationMs: event.durationMs,
                  }
                : s
            );
          }

          return [
            ...prev,
            {
              stage: event.stage,
              module: event.module,
              status:
                event.stage === "COMPLETE" ||
                event.stage === "EXTRACTION_COMPLETE"
                  ? "done"
                  : event.stage === "FAILED" || event.stage === "SKIPPED"
                    ? "error"
                    : "active",
              filesProcessed: event.filesProcessed,
              totalFiles: event.totalFiles,
              message: event.message,
              durationMs: event.durationMs,
            },
          ];
        });
      });

      es.addEventListener("done", () => {
        setRunning(false);
        setCompleted(true);
        if (timerRef.current) clearInterval(timerRef.current);
        es.close();
      });

      es.addEventListener("error", (e) => {
        // SSE error could be connection issue or extraction error
        if (es.readyState === EventSource.CLOSED) {
          setRunning(false);
          if (!completed) setError("Connection to extraction stream lost");
          es.close();
        }
      });

      es.onerror = () => {
        // Reconnection failed
        setRunning(false);
        es.close();
      };
    },
    [completed]
  );

  async function handleTriggerFull() {
    setError(null);
    setCompleted(false);
    setStages([]);
    setCurrentStage(null);

    try {
      const res = await api.triggerExtraction(
        "/mnt/source",
        "/mnt/classpath.txt"
      );
      setJobId(res.jobId);
      setRunning(true);
      setStartTime(Date.now());
      setElapsed(0);
      connectSSE(res.jobId);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  // Overall progress
  const activeStage = stages.find((s) => s.status === "active");
  const progressPercent =
    activeStage && activeStage.totalFiles > 0
      ? Math.round(
          (activeStage.filesProcessed / activeStage.totalFiles) * 100
        )
      : 0;

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold">Extraction</h2>

      {/* Source Status */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center justify-between text-sm">
            <span className="flex items-center gap-2">
              <Database className="h-4 w-4" />
              Source Configuration
            </span>
            {sourceStatus && (
              <Badge
                variant="outline"
                className={
                  sourceStatus.resolved
                    ? "border-green-500 text-green-500"
                    : "border-red-500 text-red-500"
                }
              >
                {sourceStatus.resolved ? "Connected" : "Not Found"}
              </Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {sourceStatus ? (
            <div className="space-y-1 text-xs">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Strategy</span>
                <span className="font-mono">{sourceStatus.strategy}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Source Root</span>
                <span className="font-mono">{sourceStatus.sourceRoot}</span>
              </div>
            </div>
          ) : (
            <Skeleton className="h-[40px]" />
          )}
        </CardContent>
      </Card>

      {/* Controls */}
      <div className="flex items-center gap-3">
        <Button
          onClick={handleTriggerFull}
          disabled={running}
          className="gap-2"
        >
          {running ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Play className="h-4 w-4" />
          )}
          {running ? "Extraction Running..." : "Full Extraction"}
        </Button>

        {running && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Clock className="h-4 w-4" />
            {formatDuration(elapsed)}
          </div>
        )}

        {completed && !running && (
          <Badge
            variant="outline"
            className="border-green-500 text-green-500 gap-1"
          >
            <CheckCircle className="h-3 w-3" />
            Complete — {formatDuration(elapsed)}
          </Badge>
        )}

        {error && (
          <Badge
            variant="outline"
            className="border-red-500 text-red-500 gap-1"
          >
            <AlertCircle className="h-3 w-3" />
            {error}
          </Badge>
        )}
      </div>

      {/* Progress */}
      {(running || stages.length > 0) && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">
              {running ? "Live Progress" : "Extraction Result"}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {/* Progress bar */}
            {activeStage && (
              <div className="mb-4">
                <div className="mb-1 flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    {activeStage.module
                      ? `${activeStage.module} / ${activeStage.stage}`
                      : activeStage.stage}
                  </span>
                  <span className="font-mono">
                    {activeStage.filesProcessed.toLocaleString()} /{" "}
                    {activeStage.totalFiles.toLocaleString()} files (
                    {progressPercent}%)
                  </span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-secondary">
                  <div
                    className="h-full rounded-full bg-primary transition-all duration-500"
                    style={{ width: `${progressPercent}%` }}
                  />
                </div>
                {activeStage.message && (
                  <p className="mt-1 text-[10px] text-muted-foreground">
                    {activeStage.message}
                  </p>
                )}
              </div>
            )}

            <Separator className="my-3" />

            {/* Stage timeline */}
            <div className="space-y-1">
              {stages.map((s, i) => (
                <div
                  key={`${s.stage}-${s.module}-${i}`}
                  className="flex items-center gap-3 py-1 text-xs"
                >
                  {/* Status icon */}
                  {s.status === "done" ? (
                    <CheckCircle className="h-4 w-4 shrink-0 text-green-500" />
                  ) : s.status === "active" ? (
                    <Loader2 className="h-4 w-4 shrink-0 animate-spin text-primary" />
                  ) : s.status === "error" ? (
                    <AlertCircle className="h-4 w-4 shrink-0 text-amber-500" />
                  ) : (
                    <Circle className="h-4 w-4 shrink-0 text-muted-foreground" />
                  )}

                  {/* Stage name */}
                  <span
                    className={cn(
                      "w-32",
                      s.status === "active"
                        ? "font-medium text-foreground"
                        : "text-muted-foreground"
                    )}
                  >
                    {s.stage}
                    {s.module && (
                      <span className="ml-1 text-[10px] opacity-60">
                        ({s.module})
                      </span>
                    )}
                  </span>

                  {/* File count */}
                  <span className="w-32 font-mono text-muted-foreground">
                    {s.totalFiles > 0
                      ? `${s.filesProcessed.toLocaleString()} / ${s.totalFiles.toLocaleString()}`
                      : ""}
                  </span>

                  {/* Duration */}
                  <span className="w-20 font-mono text-muted-foreground">
                    {s.durationMs ? formatDuration(s.durationMs) : ""}
                  </span>

                  {/* Message */}
                  {s.message && (
                    <span className="truncate text-[10px] text-muted-foreground">
                      {s.message}
                    </span>
                  )}
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
