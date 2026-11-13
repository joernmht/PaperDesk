// The comment macros of a PaperDesk paper (\pdmark, \pdnote) and the reMarkable marks; no DOM, testable in node.

// ------------------------------------------------------------------ parsing the comment macros

const MACRO = /\\(pdmark|pdnote|rmnote|rmhl|rmdel)(?![A-Za-z@])/g;

/** End index (exclusive, after the closing brace) of the group opening at text[pos] == open, or -1. */
export function group(text, pos, open = "{", close = "}") {
  if (text[pos] !== open) return -1;
  let depth = 0;
  for (let i = pos; i < text.length; i++) {
    const c = text[i];
    if (c === "\\") { i++; continue; }                     // \{ \} \\ \% are not structure
    if (c === "%") {                                       // a LaTeX comment runs to the end of the line
      const nl = text.indexOf("\n", i); if (nl < 0) return -1; i = nl; continue;
    }
    if (open === "{" && c === "{") depth++;
    else if (open === "[" && c === "{") { const e = group(text, i); if (e < 0) return -1; i = e - 1; continue; }
    else if (c === open) depth++;
    if (c === close) { depth--; if (depth === 0) return i + 1; }
  }
  return -1;
}

/** All top-level comment macros in the text, in order. Nested ones are part of their parent's text. */
export function parseComments(text) {
  const out = [];
  MACRO.lastIndex = 0;
  let m;
  while ((m = MACRO.exec(text))) {
    // skip macros inside a LaTeX comment line
    const ls = text.lastIndexOf("\n", m.index) + 1;
    if (/(^|[^\\])%/.test(text.slice(ls, m.index))) continue;
    const kind = m[1];
    let p = m.index + m[0].length;
    const c = { kind, from: m.index, readOnly: kind.startsWith("rm") };
    if (kind === "rmnote" && text[p] === "[") { const e = group(text, p, "[", "]"); if (e < 0) continue; c.opt = text.slice(p + 1, e - 1); p = e; }
    const e1 = group(text, p); if (e1 < 0) continue;
    if (kind === "pdmark") {
      const e2 = group(text, e1); if (e2 < 0) continue;
      Object.assign(c, { aFrom: p + 1, aTo: e1 - 1, cFrom: e1 + 1, cTo: e2 - 1, to: e2 });
    } else if (kind === "rmhl" || kind === "rmdel") {
      Object.assign(c, { aFrom: p + 1, aTo: e1 - 1, to: e1 });
    } else {
      Object.assign(c, { cFrom: p + 1, cTo: e1 - 1, to: e1 });
    }
    c.marked = c.aFrom != null ? text.slice(c.aFrom, c.aTo) : null;
    c.comment = c.cFrom != null ? unescape(text.slice(c.cFrom, c.cTo)) : null;
    out.push(c);
    MACRO.lastIndex = c.to;
  }
  return out;
}

const unescape = (s) => s.replace(/\\([{}%])/g, "$1");
export const escapeComment = (s) => s.replace(/\s*\n\s*/g, " ").replace(/([{}%])/g, "\\$1");


/** Why a selection cannot be wrapped as \pdmark, or "" if it can. */
export function selectionProblem(s) {
  if (/\n[ \t]*\n/.test(s)) return "The selection crosses a paragraph break. Mark text within one paragraph.";
  if (/\\(begin|end)\s*\{/.test(s)) return "The selection crosses the start or end of an environment (\\begin / \\end). Mark text inside it.";
  if (/(^|[^\\])%/.test(s)) return "The selection contains a LaTeX comment (%). Mark text without it.";
  let d = 0;
  for (let i = 0; i < s.length; i++) {
    if (s[i] === "\\") { i++; continue; }
    if (s[i] === "{") d++;
    if (s[i] === "}" && --d < 0) break;
  }
  if (d !== 0) return "The selection has unbalanced braces { }. Select a whole group, e.g. all of {\\em …}.";
  if (/\\(pdmark|pdnote|rmnote|rmhl|rmdel)(?![A-Za-z@])/.test(s)) return "The selection already contains a comment. Pick text without one.";
  return "";
}

