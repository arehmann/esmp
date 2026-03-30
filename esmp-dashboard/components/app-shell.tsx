"use client";

import { useState } from "react";
import { Providers } from "./providers";
import { Sidebar } from "./sidebar";
import { SearchCommand } from "./search-command";

export function AppShell({ children }: { children: React.ReactNode }) {
  const [searchOpen, setSearchOpen] = useState(false);

  return (
    <Providers>
      <div className="flex h-screen overflow-hidden">
        <Sidebar onSearchOpen={() => setSearchOpen(true)} />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
      <SearchCommand open={searchOpen} onOpenChange={setSearchOpen} />
    </Providers>
  );
}
