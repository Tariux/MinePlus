/* Headless tests for mineplus_generic_display.js — run with `node test/plugin.test.js`.
 *
 * Loads the plugin source in a vm sandbox with a stubbed Blockbench API, runs
 * the real onload() and the real action clicks, and asserts the behaviours
 * added in v3.2+: tools toggle, mineplus format with animation support, and
 * the old-model conversion that keeps cubes/bones/textures/display/animations.
 * v5.0 reverts the Studio experiment and hardens the convert path: after any
 * conversion the GLOBAL format is verified (the Display and Animate tabs are
 * gated by Format.display_mode / Format.animation_mode), with a select()
 * fallback when the native convertTo() does not land.
 *
 * The sandbox mirrors the REAL Blockbench globals: the open project is
 * `Project` (and Blockbench.Project), and there is no `Model` global — that
 * mismatch was the v4.1 bug where convert/display silently did nothing on
 * old models.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const PLUGIN_SRC = fs.readFileSync(
  path.join(__dirname, '..', 'mineplus_generic_display.js'), 'utf8');

/* ------------------------------------------------------------ stub env */

const logLines = [];
function log(line) { logLines.push(String(line)); }

const localStorageData = {};
const actions = {};          // id -> {id, name, condition, click}
const menuBar = { tools: [] };
const registeredFormats = {};
const hooks = {};            // event -> [handlers]

function FakeCube() { this.type = 'cube'; }
function FakeGroup(opts) { Object.assign(this, { export: true }, opts); }

function makeProject() {
  return {
    name: 'test_model',
    uuid: 'project-uuid',
    format: { id: 'bb_model' },
    mode: 'edit',
    elements: [new FakeCube(), new FakeCube(), new FakeCube()],
    groups: [new FakeGroup({ name: 'bone_a' }), new FakeGroup({ name: 'bone_b', export: false })],
    textures: [{ name: 'tex.png' }],
    animations: { idle: {}, fire: {} },
    display: { thirdperson_righthand: { rotation: [0, 0, 0] } }
  };
}

const sandbox = {
  console: { log: log, warn: log, error: log },
  localStorage: {
    getItem: k => (k in localStorageData ? localStorageData[k] : null),
    setItem: (k, v) => { localStorageData[k] = String(v); },
    removeItem: k => { delete localStorageData[k]; }
  },
  setTimeout: fn => fn(),
  document: {
    createElement: () => ({ type: '', click() {}, set accept(v) {}, set onchange(v) {} })
  },
  FileReader: function () { this.readAsText = function () { }; },
  Blockbench: {
    showQuickMessage: msg => log('quick message: ' + msg),
    dispatchEvent: () => {},
    on: (event, handler) => { (hooks[event] = hooks[event] || []).push(handler); },
    off: (event, handler) => { hooks[event] = (hooks[event] || []).filter(h => h !== handler); }
  },
  Plugin: {
    register: (id, def) => { sandbox.__plugin = def; sandbox.__pluginId = id; }
  },
  Action: function (id, opts) { this.id = id; this.name = opts.name; this.condition = opts.condition; this.click = opts.click; },
  ModelFormat: function (opts) {
    // like Blockbench's real Format.select(): sets the global active format
    // (and the open project's format via the project object)
    opts.select = function () {
      sandbox.__selectedFormat = this;
      sandbox.Format = this; // real select() sets the page-scope global `Format`
      if (sandbox.Project) sandbox.Project.format = this;
    };
    registeredFormats[opts.id] = opts;
    sandbox.Formats[opts.id] = opts;
    this.id = opts.id;
  },
  Formats: {},
  MenuBar: {
    addAction: (action, menu) => { if (menu === 'tools') menuBar.tools.push(action); actions[action.id] = action; },
    removeAction: action => { delete actions[action.id]; menuBar.tools = menuBar.tools.filter(a => a !== action); }
  },
  ModelProject: { properties: {} },
  Codecs: { project: { load_filter: { extensions: ['bbmodel'] } } },
  Canvas: { updateAll: () => {}, updateViewMode: () => {} },
  Mode: { edit: {}, select: () => {} },
  Modes: { display: { id: 'display', select() { this.selected = true; } } },
  Panel: function (id, opts) {
    this.id = id; this.options = opts; this.node = null;
    sandbox.Panel.stored[id] = this;
  },
  Dialog: function (opts) {
    this.options = opts; this.shown = false;
    if (opts && opts.form) {
      // Blockbench 5.x: dialog.form is an InputForm object (js/interface/
      // form.ts) — values only through getResult(), no raw `{value}` fields.
      const def = opts.form;
      this.form = {
        getResult: function () {
          const result = {};
          for (const key in def) {
            const el = def[key];
            if (el && typeof el === 'object' && 'value' in el) result[key] = el.value;
            else if (el && el.options) result[key] = Object.keys(el.options)[0];
            else result[key] = '';
          }
          return result;
        }
      };
    }
    sandbox.Dialog.last = this;
  },
  Cube: FakeCube,
  Group: FakeGroup
};
sandbox.Panel.stored = {};
sandbox.Dialog.last = null;
sandbox.Dialog.prototype.show = function () { this.shown = true; };
sandbox.Panel.prototype.delete = function () { delete sandbox.Panel.stored[this.id]; };
// No `Model` and no `Project` before a project is opened — like real Blockbench.

function load() {
  vm.createContext(sandbox);
  vm.runInContext(PLUGIN_SRC, sandbox, { filename: 'mineplus_generic_display.js' });
  if (!sandbox.__plugin) throw new Error('Plugin.register was not called');
}

/* --------------------------------------------------------------- tests */

let failures = 0;
function check(name, cond) {
  if (cond) { console.log('PASS  ' + name); } else { console.log('FAIL  ' + name); failures++; }
}

load();

// 1. onload registers the toggle plus every tool, and the mpmodel format.
sandbox.__plugin.onload();
const toolIds = menuBar.tools.map(a => a.id);
check('onload runs without throwing and registers actions', toolIds.length > 0);
check('toggle action registered', toolIds.includes('mineplus_toggle_tools'));
check('convert action registered', toolIds.includes('mineplus_convert'));
check('open-and-migrate action registered', toolIds.includes('mineplus_open_migrate'));
check('display editor registered', toolIds.includes('mineplus_display_editor'));
const fmt = registeredFormats.mineplus_model;
check('mineplus_model format registered', !!fmt);
check('format enables animation mode (Animate tab exists)', fmt && fmt.animation_mode === true);
check('format enables display mode', fmt && fmt.display_mode === true);

// 2. Toggle hides every other Mineplus action and can toggle back.
const editor = actions.mineplus_display_editor;
check('tools visible by default', editor.condition() === true);
actions.mineplus_toggle_tools.click();
check('toggle off hides tools', editor.condition() === false);
check('toggle itself stays visible', actions.mineplus_toggle_tools.condition === undefined);
actions.mineplus_toggle_tools.click();
check('toggle on shows tools again', editor.condition() === true);
check('toggle state remembered', localStorageData.mineplus_tools_visible === '1');

// 3. Conversion of an old-format model keeps all content and switches format.
// REGRESSION (v4.2.0): the sandbox has the real Blockbench globals — `Project`
// only, no `Model` — and conversion must still work end to end. On v4.1 the
// plugin required a `Model` global that does not exist in Blockbench, so the
// convert tool and every display tool silently did nothing on old models.
// DisplayMode exists in the real app from startup; the convert step now drops
// the designer into display mode via repairDisplayPanel.
sandbox.DisplayMode = { display_slot: null, vue: null, load() {} };
sandbox.Interface = { Panels: { display: { inside_vue: { _data: {} } } } };
sandbox.Project = makeProject();
actions.mineplus_convert.click();
const project = sandbox.Project;
check('converted to mineplus format (via Project global, old-model bug fixed)',
  project.format && project.format.id === 'mineplus_model');
check('format switched via Format.select() (Display tab global updated)', sandbox.__selectedFormat
  && sandbox.__selectedFormat.id === 'mineplus_model');
check('cubes kept (3)', project.elements.length === 3);
check('bones kept (2, incl. non-exported)', project.groups.length === 2);
check('textures kept (1)', project.textures.length === 1);
check('animations kept (2 — Animate tab content survives)', Object.keys(project.animations).length === 2);
check('display block kept (1 context)', Object.keys(project.display).length === 1);
check('template stamp added', project.mineplus && project.mineplus.plugin === 'mineplus_generic_display');

// 3a. When the old format is still the active global and Blockbench's native
// ModelFormat.convertTo() is available, it is used — it also migrates box UV,
// bone-rig grouping and rotation limits, which old .bbmodel files need.
sandbox.Project = makeProject();
sandbox.Blockbench.Format = { id: 'bb_model' };
let nativeUsed = false;
fmt.convertTo = function () { nativeUsed = true; this.select(); };
actions.mineplus_convert.click();
check('convert uses native convertTo() when available',
  nativeUsed && sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model');

// 3b. …and conversion still succeeds when native convertTo throws.
sandbox.Project = makeProject();
sandbox.Blockbench.Format = { id: 'bb_model' };
sandbox.__selectedFormat = null;
fmt.convertTo = function () { throw new Error('native convert failed'); };
actions.mineplus_convert.click();
check('convert falls back to select when native convertTo() throws',
  sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model'
    && sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');
delete fmt.convertTo;
sandbox.Blockbench.Format = undefined;

// 3c. REGRESSION (v5.0): native convertTo() is used even when the global
// format is unset (fresh Blockbench state). The old guard skipped the native
// path whenever `Blockbench.Format` was falsy — on exactly the old models
// that had never been touched by the plugin, so Display stayed missing.
sandbox.Project = makeProject();
sandbox.Blockbench.Format = null;
let nativeUsedUnset = false;
fmt.convertTo = function () { nativeUsedUnset = true; this.select(); };
actions.mineplus_convert.click();
check('native convertTo() used even when global Format is unset',
  nativeUsedUnset && sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model');

// 3d. REGRESSION (v5.0): if native convertTo() runs but does not update the
// global format (older builds / odd states), the plugin falls back to select
// so the Display tab flags (`display_mode`/`animation_mode`) still come on.
sandbox.Project = makeProject();
sandbox.Blockbench.Format = { id: 'free' }; // old generic format: Animate yes, Display no
sandbox.Format = sandbox.Blockbench.Format; // page-scope global is stale too
sandbox.__selectedFormat = null;
fmt.convertTo = function () { /* no-op: did not land */ };
actions.mineplus_convert.click();
check('convert verifies global format and falls back to select when convertTo() did not land',
  sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model'
    && sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model'
    && sandbox.Format && sandbox.Format.display_mode === true
    && sandbox.Format.animation_mode === true);
delete fmt.convertTo;
sandbox.Blockbench.Format = undefined;

// 4. Converting an already-mineplus project is a no-op that still succeeds.
actions.mineplus_convert.click();
check('second convert is idempotent', project.format.id === 'mineplus_model'
  && project.elements.length === 3 && Object.keys(project.animations).length === 2);

// 5b. Reopened mineplus project: even when format is already mineplus, the
// global format (which drives the Display/Animate tabs) gets re-selected —
// the "display but no Animate/Display tab" bug on old models.
sandbox.Project = makeProject();
sandbox.Project.format = fmt;         // model says mineplus…
sandbox.__selectedFormat = null;      // …but the global format is stale
actions.mineplus_convert.click();
check('reopened model re-selects global format (Display tab fix)',
  sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 5c. The project_loaded hook runs the auto-apply (logged, not silently caught).
const project2 = makeProject();
sandbox.Project = project2;
sandbox.__selectedFormat = null;
(hooks.project_loaded || []).forEach(h => h());
check('project_loaded hook auto-applies mineplus format',
  project2.format && project2.format.id === 'mineplus_model'
    && sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 5d. Regression for the reported crash: "Uncaught TypeError: Cannot read
// properties of undefined (reading 'value')" at display editor onButton —
// on Blockbench 5.x dialog.form is an InputForm, so `form.context.value` etc.
// were undefined. Button 2 (Clear context) is the exact path from the log.
sandbox.Project = makeProject();
sandbox.Project.format = fmt;
actions.mineplus_display_editor.click();
const editorDialog = sandbox.Dialog.last;
check('display editor dialog builds an InputForm-style form',
  !!editorDialog && editorDialog.form && typeof editorDialog.form.getResult === 'function');
let editorCrash = null;
try { editorDialog.options.onButton(2); editorDialog.options.onButton(1); }
catch (e) { editorCrash = e; }
check('editor onButton reads values via getResult() (no undefined .value crash)',
  editorCrash === null);
const applied = sandbox.Project.display.thirdperson_righthand || {};
check('editor Apply writes clamped rotation into the display block',
  Array.isArray(applied.rotation) && applied.rotation.length === 3
    && applied.rotation.every(n => typeof n === 'number' && !isNaN(n)));

// 5e. Regression for the still-empty Display tab: the panel only renders its
// slot list and sliders while display MODE is active (condition
// {modes: ['display']}); the plugin's repair must therefore enter display
// mode itself when the current format allows it.
sandbox.Modes = {
  options: { display: { id: 'display', select() { sandbox.Modes.displaySelected = true; } } },
  displaySelected: false
};
sandbox.Mode = { id: 'edit' };
// Real Blockbench keeps the active format as a page-scope global; the repair
// reads its display_mode flag before entering display mode.
sandbox.Format = { id: 'mineplus_model', display_mode: true, animation_mode: true };
sandbox.DisplayMode = {
  display_slot: null, vue: null, loadedSlot: null,
  load: function (key) { this.loadedSlot = key; this.vue._data.slot = { key: key }; }
};
sandbox.Interface = { Panels: { display: { inside_vue: { _data: {} } } } };
(hooks.select_mode || []).forEach(h => h({ mode: { id: 'display' } }));
check('empty Display panel repair enters display mode (default tools)',
  sandbox.Modes.displaySelected === true
    && sandbox.DisplayMode.loadedSlot === 'thirdperson_righthand'
    && !!sandbox.DisplayMode.vue._data.slot);
// When the format does not allow display mode, the repair must not throw.
delete sandbox.Modes;
sandbox.Modes = { display: { select() { throw new Error('nope'); } } };
let repairFail = null;
try { (hooks.select_mode || []).forEach(h => h({ mode: { id: 'edit' } })); }
catch (e) { repairFail = e; }
check('display panel repair failure is caught and logged, not thrown',
  repairFail === null);
delete sandbox.DisplayMode;
delete sandbox.Interface;

// 6. Studio was reverted (v5.0): it added nothing the default UI doesn't do
// and confused designers. The tool and its tests are gone on purpose.
check('studio action is gone (reverted)', actions.mineplus_studio === undefined);

// 6b. onunload removes the actions.
sandbox.__plugin.onunload();
check('onunload runs cleanly', actions.mineplus_display_editor === undefined);

console.log(failures === 0
  ? `\nAll ${'checks'} passed.`
  : `\n${failures} check(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
