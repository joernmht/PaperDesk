// PaperDesk LaTeX editor: CodeMirror 6 in the app's WebView. Bundled by ./build.sh into
// app/src/main/assets/editor/editor.js (no CDN, works offline). Kotlin side: ui/Editor.kt.
//
// Comments live in the LaTeX source as macros (defined in the paper's preamble by the server):
//   \pdmark{marked text}{comment}   a comment on a span of text
//   \pdnote{comment}                a comment at a point
//   \rmnote[p.N]{…} \rmhl{…} \rmdel{…}   handwritten reMarkable marks (remarkable-notes skill)
// Unless "show source" is on, the macro syntax is hidden: marks show only the marked text, notes are
// small widgets. A tap on either reports the comment to Kotlin (Android.onCommentTap).

import { EditorState, StateField, StateEffect, Compartment, Text } from "@codemirror/state";
import { EditorView, Decoration, WidgetType, keymap, drawSelection, highlightActiveLine } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab, undo } from "@codemirror/commands";
import { StreamLanguage, syntaxHighlighting, HighlightStyle, bracketMatching } from "@codemirror/language";
import { stex } from "@codemirror/legacy-modes/mode/stex";
import { search, SearchQuery, setSearchQuery, findNext, findPrevious, highlightSelectionMatches } from "@codemirror/search";
import { tags as t } from "@lezer/highlight";

const host = window.Android || {   // no bridge (desktop browser while developing): log instead
  onReady() {}, onChange(d) { console.log("dirty", d); }, onSelection() {}, onDraft() {},
  onCommentTap(j) { console.log("tap", j); },
};

import { parseComments, escapeComment as escape, selectionProblem } from "./comments.js";

// ------------------------------------------------------------------ decorations

class FormWidget extends WidgetType {
  constructor(shape, index, title) { super(); this.shape = shape; this.index = index; this.title = title; }
  eq(o) { return o.shape === this.shape && o.index === this.index && o.title === this.title; }
  toDOM() {
    const s = document.createElement("span");
    s.className = "pd-w pd-" + this.shape;
    s.dataset.ci = String(this.index);
    s.title = this.title;
    s.setAttribute("role", "button");
    s.setAttribute("aria-label", this.title);
    return s;
  }
  ignoreEvent() { return false; }
}

const hide = Decoration.replace({});
const markPd = Decoration.mark({ class: "pd-mark" });
const markHl = Decoration.mark({ class: "pd-hl" });
const markDel = Decoration.mark({ class: "pd-del" });

const setShowSource = StateEffect.define();

function build(text, showSource) {
  const comments = parseComments(text);
  if (showSource) return { comments, deco: Decoration.none, atomic: Decoration.none };
  const ranges = [], atomic = [];
  const hidden = (a, b) => { if (b > a) { ranges.push(hide.range(a, b)); atomic.push(hide.range(a, b)); } };
  comments.forEach((c, i) => {
    if (c.kind === "pdnote" || c.kind === "rmnote" || c.aFrom === c.aTo) {
      const w = Decoration.replace({ widget: new FormWidget(c.kind === "rmnote" ? "square" : c.kind === "pdnote" ? "circle" : "tri", i,
        (c.readOnly ? "reMarkable: " : "Comment: ") + (c.comment || c.marked || "")) });
      ranges.push(w.range(c.from, c.to)); atomic.push(w.range(c.from, c.to));
      return;
    }
    hidden(c.from, c.aFrom);
    ranges.push((c.kind === "pdmark" ? markPd : c.kind === "rmhl" ? markHl : markDel).range(c.aFrom, c.aTo));
    hidden(c.aTo, c.to);
  });
  return { comments, deco: Decoration.set(ranges, true), atomic: Decoration.set(atomic, true) };
}

const commentField = StateField.define({
  create(state) { return { show: false, ...build(state.doc.toString(), false) }; },
  update(v, tr) {
    let show = v.show;
    for (const e of tr.effects) if (e.is(setShowSource)) show = e.value;
    if (!tr.docChanged && show === v.show) return v;
    return { show, ...build(tr.state.doc.toString(), show) };
  },
  provide: (f) => [
    EditorView.decorations.from(f, (v) => v.deco),
    EditorView.atomicRanges.of((view) => view.state.field(f).atomic),
  ],
});

// ------------------------------------------------------------------ look

const highlight = HighlightStyle.define([
  { tag: [t.tagName, t.macroName], color: "var(--blue)", fontWeight: "500" },
  { tag: t.keyword, color: "var(--red)" },
  { tag: [t.atom, t.number, t.string], color: "var(--olive)" },
  { tag: t.comment, color: "var(--muted)", fontStyle: "italic" },
  { tag: t.bracket, color: "var(--muted)" },
]);

const theme = EditorView.theme({
  "&": { height: "100%", fontSize: "var(--fs, 15px)", color: "var(--ink)", backgroundColor: "var(--paper)" },
  ".cm-scroller": { fontFamily: "ui-monospace, 'Roboto Mono', 'Droid Sans Mono', monospace", lineHeight: "1.55" },
  ".cm-content": { padding: "8px 0 40vh 0", caretColor: "var(--blue)" },
  ".cm-line": { padding: "0 12px" },
  "&.cm-focused": { outline: "none" },
  ".cm-cursor": { borderLeftColor: "var(--blue)", borderLeftWidth: "2px" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "var(--sel) !important" },
  ".cm-activeLine": { backgroundColor: "var(--active)" },
  ".cm-searchMatch": { backgroundColor: "var(--yellow-soft)", outline: "1px solid var(--yellow)" },
  ".cm-searchMatch-selected": { backgroundColor: "var(--yellow)", color: "#141414" },
  ".cm-panels": { display: "none" },           // search is driven from the app's top bar
});

// ------------------------------------------------------------------ the view

const readOnly = new Compartment();
let cleanDoc = null, lastDirty = false, lastSel = false, draftTimer = 0;

const notifier = EditorView.updateListener.of((u) => {
  if (u.docChanged) {
    const dirty = cleanDoc ? !u.state.doc.eq(cleanDoc) : false;
    if (dirty !== lastDirty) { lastDirty = dirty; host.onChange(dirty); }
    clearTimeout(draftTimer);
    if (dirty) draftTimer = setTimeout(() => host.onDraft(view.state.doc.toString()), 1500);
  }
  if (u.selectionSet) {
    const has = !u.state.selection.main.empty;
    if (has !== lastSel) { lastSel = has; host.onSelection(has); }
  }
});

function report(c, i) {
  const line = view.state.doc.lineAt(c.from);
  host.onCommentTap(JSON.stringify({ index: i, kind: c.kind, from: c.from, to: c.to, marked: c.marked, comment: c.comment,
    readOnly: c.readOnly, opt: c.opt || null, line: line.number, col: c.from - line.from + 1 }));
}

const taps = EditorView.domEventHandlers({
  click(e, v) {
    const f = v.state.field(commentField);
    const w = e.target.closest && e.target.closest("[data-ci]");
    if (w) { const i = +w.dataset.ci; if (f.comments[i]) report(f.comments[i], i); return true; }
    if (f.show) return false;
    const pos = v.posAtCoords({ x: e.clientX, y: e.clientY });
    if (pos == null) return false;
    const i = f.comments.findIndex((c) => c.aFrom != null && pos >= c.aFrom && pos <= c.aTo && v.state.selection.main.empty);
    if (i >= 0) report(f.comments[i], i);
    return false;   // the cursor still moves there
  },
});

const view = new EditorView({
  parent: document.getElementById("ed"),
  state: EditorState.create({
    doc: "",
    extensions: [
      history(), drawSelection(), highlightActiveLine(), bracketMatching(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      StreamLanguage.define(stex), syntaxHighlighting(highlight),
      search({ top: true }), highlightSelectionMatches(),
      EditorView.lineWrapping, commentField, theme, notifier, taps,
      readOnly.of([]),
      EditorView.contentAttributes.of({ autocapitalize: "off", autocorrect: "off", spellcheck: "false" }),
    ],
  }),
});

// ------------------------------------------------------------------ helpers for the bridge

function posOf(line, col) {
  const doc = view.state.doc;
  const l = doc.line(Math.min(Math.max(1, line | 0), doc.lines));
  return Math.min(l.from + Math.max(0, (col | 0) - 1), l.to);
}

function findComment(from, kind) {
  const f = view.state.field(commentField);
  return f.comments.find((c) => c.from === from && c.kind === kind) || null;
}

const dispatch = (spec) => { view.dispatch(spec); return ""; };

window.pd = {
  /** Show [text]; [clean] is the server's version (differs when an unsaved draft is restored). */
  load(text, clean, line, col) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text }, selection: { anchor: 0 } });
    cleanDoc = Text.of((clean == null ? text : clean).split("\n"));
    lastDirty = !view.state.doc.eq(cleanDoc);
    host.onChange(lastDirty);
    if (line) this.jump(line, col || 1);
  },
  getText() { return view.state.doc.toString(); },
  markClean() { cleanDoc = view.state.doc; lastDirty = false; host.onChange(false); },
  setReadOnly(on) { view.dispatch({ effects: readOnly.reconfigure(on ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []) }); },
  setShowSource(on) { view.dispatch({ effects: setShowSource.of(!!on) }); },
  setFontSize(px) { document.documentElement.style.setProperty("--fs", px + "px"); view.requestMeasure(); },
  setDark(on) { document.body.classList.toggle("dark", !!on); },
  focus() { view.focus(); },
  jump(line, col) {
    const p = posOf(line, col);
    view.dispatch({ selection: { anchor: p }, effects: EditorView.scrollIntoView(p, { y: "center" }) });
  },
  /** Jump to line:col and open the comment found there (from the Comments list). */
  openCommentAt(line, col) {
    this.jump(line, col);
    const p = posOf(line, col), f = view.state.field(commentField);
    let i = f.comments.findIndex((c) => p >= c.from && p < c.to);
    if (i < 0) { const ln = view.state.doc.lineAt(p); i = f.comments.findIndex((c) => c.from >= ln.from && c.from <= ln.to); }
    if (i >= 0) setTimeout(() => report(f.comments[i], i), 50);
  },
  find(q, backward) {
    view.dispatch({ effects: setSearchQuery.of(new SearchQuery({ search: q || "", caseSensitive: false })) });
    if (q) (backward ? findPrevious : findNext)(view);
  },
  /** Symbol bar: wrap the selection (or the cursor) in before … after. */
  snippet(before, after) {
    const r = view.state.selection.main;
    const sel = view.state.sliceDoc(r.from, r.to);
    view.dispatch({ changes: { from: r.from, to: r.to, insert: before + sel + after },
      selection: { anchor: r.from + before.length + sel.length }, scrollIntoView: true });
    view.focus();
  },
  /** "" when the comment can be added; otherwise the reason, shown to the user before asking for text. */
  checkComment() {
    const r = view.state.selection.main;
    return r.empty ? "" : selectionProblem(view.state.sliceDoc(r.from, r.to));
  },
  addComment(text) {
    const r = view.state.selection.main, c = escape(text);
    if (r.empty) return dispatch({ changes: { from: r.from, insert: "\\pdnote{" + c + "}" }, selection: { anchor: r.from + 9 + c.length } });
    const sel = view.state.sliceDoc(r.from, r.to), why = selectionProblem(sel);
    if (why) return why;
    const ins = "\\pdmark{" + sel + "}{" + c + "}";
    return dispatch({ changes: { from: r.from, to: r.to, insert: ins }, selection: { anchor: r.from + ins.length } });
  },
  editComment(from, kind, text) {
    const c = findComment(from, kind);
    if (!c || c.cFrom == null || c.readOnly) return "That comment changed in the meantime. Tap it again.";
    return dispatch({ changes: { from: c.cFrom, to: c.cTo, insert: escape(text) } });
  },
  /** Remove the comment, keep the text it marks. */
  resolve(from, kind) {
    const c = findComment(from, kind);
    if (!c) return "That comment changed in the meantime. Tap it again.";
    return dispatch({ changes: { from: c.from, to: c.to, insert: c.marked || "" } });
  },
  /** Remove the comment together with the text it marks (for \rmdel: carry out the deletion). */
  deleteMarked(from, kind) {
    const c = findComment(from, kind);
    if (!c) return "That comment changed in the meantime. Tap it again.";
    return dispatch({ changes: { from: c.from, to: c.to, insert: "" } });
  },
  undo() { undo(view); },
};

host.onReady();
