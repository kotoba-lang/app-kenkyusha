// Kenkyusha thin facade. Research-frontier logic runs in the lg-kenkyusha
// LangGraph pod (https://kenkyusha.etzhayyim.com). MCP tools/list discovers the
// three Science OS / EACN3 lifecycle NSIDs (publishFrontier / getFrontier /
// listFrontiers) via vertex_capability (Alembic 20260514_0002) and dispatches
// here; this facade then proxies the call to the pod with x-api-key auth.
// Other domain NSIDs continue to go through the legacy dispatcher.

type Env = {
  DISPATCHER_URL?: string;
  /** Base URL of the lg-kenkyusha pod (ingress). Defaults to kenkyusha.etzhayyim.com. */
  KENKYUSHA_LG_URL?: string;
  /** x-api-key forwarded to lg-kenkyusha. Stored as a Worker secret. */
  LG_KENKYUSHA_API_KEY?: string;
  ASSETS?: { fetch(request: Request): Promise<Response> };
};

const ACTOR = {
  name: "Kenkyusha",
  did: "did:web:kenkyusha.etzhayyim.com",
  nanoid: "kk8r3n5v",
};

const NSIDS = new Set([
  "com.etzhayyim.apps.kenkyusha.collectEvidence",
  "com.etzhayyim.apps.kenkyusha.coverageMap",
  "com.etzhayyim.apps.kenkyusha.detectFrontiers",
  "com.etzhayyim.apps.kenkyusha.evaluateHypothesis",
  "com.etzhayyim.apps.kenkyusha.generateHypothesis",
  "com.etzhayyim.apps.kenkyusha.getFrontier",
  "com.etzhayyim.apps.kenkyusha.listDisciplines",
  "com.etzhayyim.apps.kenkyusha.listFrontiers",
  "com.etzhayyim.apps.kenkyusha.publishFrontier",
  "com.etzhayyim.apps.kenkyusha.registerDids",
  "com.etzhayyim.apps.kenkyusha.searchEvidence",
  "com.etzhayyim.apps.kenkyusha.seedDisciplines",
  "com.etzhayyim.apps.kenkyusha.stats",
]);

// NSIDs that go directly to the lg-kenkyusha pod (Phase 2A MCP facade).
// Everything else still goes through dispatcher.etzhayyim.com.
const POD_NSIDS = new Set([
  "com.etzhayyim.apps.kenkyusha.publishFrontier",
  "com.etzhayyim.apps.kenkyusha.getFrontier",
  "com.etzhayyim.apps.kenkyusha.listFrontiers",
]);

const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    ...init,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      ...(init.headers ?? {}),
    },
  });

async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  if (request.method === "GET" || request.method === "HEAD") {
    return Object.fromEntries(new URL(request.url).searchParams.entries());
  }
  const text = await request.text();
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : {};
  } catch {
    return null;
  }
}

async function dispatch(env: Env, nsid: string, body: Record<string, unknown>, request: Request): Promise<Response> {
  const base = (env.DISPATCHER_URL ?? "https://dispatcher.etzhayyim.com").replace(/\/+$/, "");
  const headers = new Headers({ accept: "application/json", "content-type": "application/json" });
  const auth = request.headers.get("authorization");
  if (auth) headers.set("authorization", auth);
  const activeDid = request.headers.get("x-active-did");
  if (activeDid) headers.set("x-active-did", activeDid);
  const response = await fetch(`${base}/xrpc/${nsid}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  const outHeaders = new Headers(response.headers);
  outHeaders.set("access-control-allow-origin", "*");
  return new Response(response.body, { status: response.status, headers: outHeaders });
}

/**
 * Proxy lifecycle NSIDs (publishFrontier / getFrontier / listFrontiers)
 * directly to the lg-kenkyusha pod. Used by MCP tools/call dispatch and
 * by yoro for Protocol Canvas reads.
 *
 * Path mapping:
 *   publishFrontier → POST /frontiers/publish
 *   getFrontier     → GET  /frontiers/{frontier_id}/state
 *   listFrontiers   → GET  /frontiers?limit=&status=
 */
async function dispatchPod(env: Env, nsid: string, body: Record<string, unknown>): Promise<Response> {
  const base = (env.KENKYUSHA_LG_URL ?? "https://kenkyusha.etzhayyim.com").replace(/\/+$/, "");
  const headers = new Headers({ accept: "application/json", "content-type": "application/json" });
  if (env.LG_KENKYUSHA_API_KEY) headers.set("x-api-key", env.LG_KENKYUSHA_API_KEY);

  let url = base;
  let init: RequestInit = { headers };
  switch (nsid) {
    case "com.etzhayyim.apps.kenkyusha.publishFrontier": {
      url += "/frontiers/publish";
      init = { method: "POST", headers, body: JSON.stringify(body) };
      break;
    }
    case "com.etzhayyim.apps.kenkyusha.getFrontier": {
      const fid = String((body as Record<string, unknown>).frontier_id ?? "").trim();
      if (!fid) {
        return new Response(JSON.stringify({ error: "frontier_id required" }), {
          status: 400,
          headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
        });
      }
      url += `/frontiers/${encodeURIComponent(fid)}/state`;
      init = { method: "GET", headers };
      break;
    }
    case "com.etzhayyim.apps.kenkyusha.listFrontiers": {
      const params = new URLSearchParams();
      const limit = String((body as Record<string, unknown>).limit ?? "");
      const status = String((body as Record<string, unknown>).status ?? "");
      if (limit) params.set("limit", limit);
      if (status) params.set("status", status);
      url += params.toString() ? `/frontiers?${params}` : "/frontiers";
      init = { method: "GET", headers };
      break;
    }
    default:
      return new Response(JSON.stringify({ error: "unsupported_pod_nsid", nsid }), {
        status: 404,
        headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
      });
  }

  const response = await fetch(url, init);
  const outHeaders = new Headers(response.headers);
  outHeaders.set("access-control-allow-origin", "*");
  return new Response(response.body, { status: response.status, headers: outHeaders });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET,POST,OPTIONS",
          "access-control-allow-headers": "authorization,content-type,x-active-did",
        },
      });
    }
    if (url.pathname === "/health") return json({ ok: true, actor: ACTOR.name, did: ACTOR.did });
    if (url.pathname === "/_app/meta") return json({ ...ACTOR, nsids: [...NSIDS] });
    if (url.pathname.startsWith("/xrpc/")) {
      const nsid = url.pathname.slice("/xrpc/".length);
      if (!NSIDS.has(nsid)) return json({ error: "unsupported_nsid", nsid }, { status: 404 });
      if (request.method !== "GET" && request.method !== "POST") return json({ error: "method_not_allowed" }, { status: 405 });
      const body = await readBody(request);
      if (body === null) return json({ error: "invalid_json" }, { status: 400 });
      if (POD_NSIDS.has(nsid)) return dispatchPod(env, nsid, body);
      return dispatch(env, nsid, body, request);
    }
    if (env.ASSETS) return env.ASSETS.fetch(request);
    return json({ error: "not_found" }, { status: 404 });
  },
};
