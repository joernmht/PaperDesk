// node test.mjs — checks the macro parser and the selection rules
import { parseComments, escapeComment, selectionProblem } from "./src/comments.js";
import assert from "node:assert/strict";
const t = String.raw`Intro \pdmark{some {\em nested} text}{why \} here} and \pdnote{fix \% this}.
% \pdnote{commented out}
\rmnote[p.3]{too long} \rmhl{keep} \rmdel{drop {x}} \pdmark{broken}
\newcommand{\pdmark}[2]{#1}`;
const c = parseComments(t);
assert.deepEqual(c.map((x) => x.kind), ["pdmark", "pdnote", "rmnote", "rmhl", "rmdel"]);
assert.equal(c[0].marked, String.raw`some {\em nested} text`);
assert.equal(c[0].comment, "why } here");
assert.equal(t.slice(c[0].from, c[0].to), String.raw`\pdmark{some {\em nested} text}{why \} here}`);
assert.equal(c[1].comment, "fix % this");
assert.equal(c[2].opt, "p.3");
assert.equal(c[4].marked, "drop {x}");
assert.equal(escapeComment("a {b} 50%\nnext"), String.raw`a \{b\} 50\% next`);
assert.equal(selectionProblem("plain text"), "");
assert.match(selectionProblem("a\n\nb"), /paragraph/);
assert.match(selectionProblem(String.raw`x \end{itemize}`), /environment/);
assert.match(selectionProblem("{\\em x"), /unbalanced/);
assert.match(selectionProblem("x } {"), /unbalanced/);
assert.equal(selectionProblem(String.raw`a \{ b`), "");
console.log("comments.js: all checks pass");
