import { NextRequest } from "next/server";

const ESMP_API_URL = process.env.ESMP_API_URL || "http://localhost:8080";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  const apiPath = path.join("/");
  const search = request.nextUrl.search;
  const url = `${ESMP_API_URL}/api/${apiPath}${search}`;

  const accept = request.headers.get("Accept") || "";
  const isSSE = accept.includes("text/event-stream") || apiPath.includes("progress");

  if (isSSE) {
    // Stream SSE responses without buffering
    const res = await fetch(url, {
      headers: { Accept: "text/event-stream" },
    });

    if (!res.ok || !res.body) {
      return new Response(await res.text(), { status: res.status });
    }

    // Pass through the stream directly
    return new Response(res.body, {
      status: 200,
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
      },
    });
  }

  // Regular JSON requests
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json" },
  });

  const data = await res.text();
  return new Response(data, {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") || "application/json" },
  });
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  const apiPath = path.join("/");
  const search = request.nextUrl.search;
  const url = `${ESMP_API_URL}/api/${apiPath}${search}`;

  const body = await request.text();
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body || undefined,
  });

  const data = await res.text();
  return new Response(data, {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") || "application/json" },
  });
}
