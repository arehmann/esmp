"use client";

export function SearchCommand({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/50 pt-[20vh]" onClick={() => onOpenChange(false)}>
      <div className="w-full max-w-lg rounded-lg border border-border bg-card p-4 shadow-lg" onClick={(e) => e.stopPropagation()}>
        <p className="text-sm text-muted-foreground">Search coming soon...</p>
      </div>
    </div>
  );
}
