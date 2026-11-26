#!/usr/bin/env node
/**
 * Call the running Blockbench MCP over HTTP when this client has not exposed
 * its tools natively.
 *
 *   node bb-mcp-call.mjs <tool> '<json-args>'
 *   node bb-mcp-call.mjs get_capabilities '{"include_tools":true}'
 *   node bb-mcp-call.mjs --list              # tool names only
 *   node bb-mcp-call.mjs --list --schemas    # names + input schemas
 *   node bb-mcp-call.mjs --schema <tool>     # one tool's input schema
 *
 * Env: BB_MCP_URL (default http://localhost:6960/bb-mcp)
 */

const URL = process.env.BB_MCP_URL || "http://localhost:6960/bb-mcp";
const argv = process.argv.slice(2);
const listMode = argv[0] === "--list";
const schemaMode = argv[0] === "--schema";
const schemasMode = listMode && argv.includes("--schemas");
const tool = listMode ? null : schemaMode ? argv[1] : argv[0];
const rawArgs = schemaMode ? "{}" : argv[1] ?? "{}";

if (!tool && !listMode && !schemaMode) {
  console.error("Usage: node bb-mcp-call.mjs <tool> '<json-args>'\n       node bb-mcp-call.mjs --list [--schemas]\n       node bb-mcp-call.mjs --schema <tool>");
  process.exit(2);
}

let args;
try {
  args = JSON.parse(rawArgs);
} catch (error) {
  console.error(`Invalid JSON arguments: ${error.message}`);
  process.exit(2);
}

const headers = {
  "Content-Type": "application/json",
  Accept: "application/json, text/event-stream",
};

async function post(body, sessionId) {
  const response = await fetch(URL, {
    method: "POST",
    headers: sessionId ? { ...headers, "Mcp-Session-Id": sessionId } : headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  });
  const text = await response.text();
  return { response, text };
}

function decode(text, contentType = "") {
  if (contentType.includes("text/event-stream")) {
    const events = text
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim())
      .filter(Boolean);
    if (events.length === 0) return JSON.parse(text);
    return JSON.parse(events[events.length - 1]);
  }
  return JSON.parse(text);
}

try {
  const init = await post({
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "mineplus-model-authoring", version: "1.0.0" },
    },
  });
  const sessionId = init.response.headers.get("mcp-session-id");
  if (!sessionId) {
    console.error(`Blockbench did not issue a session id (HTTP ${init.response.status}).`);
    console.error(init.text.slice(0, 500));
    process.exit(1);
  }

  await post(
    { jsonrpc: "2.0", method: "notifications/initialized" },
    sessionId,
  );

  const call = await post(
    {
      jsonrpc: "2.0",
      id: 2,
      method: listMode || schemaMode ? "tools/list" : "tools/call",
      params: listMode || schemaMode ? {} : { name: tool, arguments: args },
    },
    sessionId,
  );
  const payload = decode(call.text, call.response.headers.get("content-type") || "");

  if (payload.error) {
    console.error(JSON.stringify(payload.error, null, 2));
    process.exit(1);
  }
  if (payload.result?.isError) {
    console.error(
      payload.result.content?.map((item) => item.text).join("\n") ||
        JSON.stringify(payload.result, null, 2),
    );
    process.exit(1);
  }

  if (listMode || schemaMode) {
    const tools = payload.result?.tools ?? [];
    if (schemaMode) {
      const found = tools.find((entry) => entry.name === tool);
      if (!found) {
        console.error(`No such tool: ${tool}`);
        process.exit(1);
      }
      console.log(JSON.stringify(found, null, 2));
      process.exit(0);
    }
    if (schemasMode) {
      console.log(JSON.stringify(tools, null, 2));
      process.exit(0);
    }
    for (const entry of tools) {
      console.log(entry.description ? `${entry.name} - ${entry.description.split("\n")[0]}` : entry.name);
    }
    process.exit(0);
  }

  const content = payload.result?.content;
  if (content?.length) {
    for (const item of content) {
      if (item.type === "text") console.log(item.text);
      else console.log(JSON.stringify(item));
    }
  }
  if (payload.result?.structuredContent) {
    console.log(JSON.stringify(payload.result.structuredContent, null, 2));
  }
} catch (error) {
  console.error(`Blockbench MCP unreachable at ${URL}: ${error.message}`);
  console.error("Open Blockbench desktop with the MCP plugin running, then check the port in its settings.");
  process.exit(1);
}
