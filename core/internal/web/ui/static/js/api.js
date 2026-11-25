// Talking to the atrium. Every call returns parsed JSON, or throws an ApiError
// whose message is safe to show the user.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status; // 0 means the atrium could not be reached
    this.code = code;
  }
}

async function request(method, path, body) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "offline", "Can't reach your atrium. Check that it is running, then try again.");
  }
  if (res.status === 204) return null;
  let data = null;
  try {
    data = await res.json();
  } catch {
    // not JSON: fall through to the generic message below
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? "error", data?.error ?? `Something went wrong (${res.status}).`);
  }
  return data;
}

export const api = {
  get: (path) => request("GET", path),
  post: (path, body = {}) => request("POST", path, body),
  put: (path, body = {}) => request("PUT", path, body),
};
