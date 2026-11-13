// The simple view: finds the LaTeX structure that editor.js shows the way Markdown would — headings as # / ## / ###,
// \textbf / \emph as bold / italic, \cite / \ref as small chips, \item as a bullet, \label hidden. No DOM, testable in node.
// The text itself is never changed by this view: it only decides what to hide or style.

import { group } from "./comments.js";

const HEAD = { part: 1, chapter: 1, section: 1, subsection: 2, subsubsection: 3, paragraph: 4, subparagraph: 4 };
const STYLE = { textbf: "b", emph: "i", textit: "i", textsl: "i", texttt: "tt", underline: "u", textsc: "sc", footnote: "fn" };
const REF = new Set(["ref", "eqref", "cref", "Cref", "autoref", "pageref", "nameref", "vref"]);
const COMMENT_MACROS = new Set(["pdmark", "pdnote", "rmnote", "rmhl", "rmdel"]);
const isCite = (n) => /^cite[a-zA-Z]*$/.test(n) || /^(paren|text|auto|foot|no|super)cite$/.test(n);

/** Heading levels for the "#" input shortcut: "#" → \section, "##" → \subsection, "###" → \subsubsection. */
export const HEADING_FOR = { 1: "section", 2: "subsection", 3: "subsubsection" };

/**
 * Items to decorate, in text order. [skip] = sorted, non-overlapping [from, to) ranges that belong to the comment
 * macros (their hidden syntax); nothing inside them is touched.
 *   head  {level, from, cFrom, cTo, to}   style {cls, from, cFrom, cTo, to}
 *   cite / ref {from, to, label}          hide {from, to}     item {from, to}
 *   env {open, env, from, to}             texcomment {from, to}
 */
export function scanSimple(text, skip = []) {
  const out = [];
  const re = /\\(?:([A-Za-z@]+)(\*?)|[^A-Za-z@])|%/g;
  let m, si = 0;
  const opt = (p) => { if (text[p] !== "[") return p; const e = group(text, p, "[", "]"); return e < 0 ? -1 : e; };
  while ((m = re.exec(text))) {
    const at = m.index;
    while (si < skip.length && skip[si][1] <= at) si++;
    if (si < skip.length && skip[si][0] <= at) { re.lastIndex = Math.max(re.lastIndex, skip[si][1]); continue; }
    if (m[0] === "%") {
      const nl = text.indexOf("\n", at), end = nl < 0 ? text.length : nl;
      out.push({ t: "texcomment", from: at, to: end });
      re.lastIndex = end;
      continue;
    }
    const name = m[1];
    if (!name || COMMENT_MACROS.has(name)) continue;
    let p = re.lastIndex;
    if (name in HEAD || name in STYLE) {
      if (name in HEAD) p = opt(p);
      if (p < 0) continue;
      const e = group(text, p); if (e < 0) continue;
      const it = { from: at, cFrom: p + 1, cTo: e - 1, to: e };
      out.push(name in HEAD ? { t: "head", level: HEAD[name], ...it } : { t: "style", cls: STYLE[name], ...it });
      re.lastIndex = p + 1;                      // the title / styled text may hold more structure
    } else if (isCite(name) || REF.has(name)) {
      if (isCite(name)) { p = opt(p); if (p >= 0) p = opt(p); }
      if (p < 0) continue;
      const e = group(text, p); if (e < 0) continue;
      const keys = text.slice(p + 1, e - 1).split(",").map((k) => k.trim()).filter(Boolean);
      out.push({ t: isCite(name) ? "cite" : "ref", from: at, to: e, label: keys.join(", ") });
      re.lastIndex = e;
    } else if (name === "label") {
      const e = group(text, p); if (e < 0) continue;
      out.push({ t: "hide", from: at, to: e });
      re.lastIndex = e;
    } else if (name === "begin" || name === "end") {
      const e = group(text, p); if (e < 0) continue;
      out.push({ t: "env", open: name === "begin", env: text.slice(p + 1, e - 1).trim(), from: at, to: e });
      re.lastIndex = e;
    } else if (name === "item") {
      p = opt(p); if (p < 0) continue;
      out.push({ t: "item", from: at, to: text[p] === " " ? p + 1 : p });
      re.lastIndex = p;
    }
  }
  return out;
}
