/* Headless tests for mineplus_generic_display.js — run with `node test/plugin.test.js`.
 *
 * Loads the plugin source in a vm sandbox with a stubbed Blockbench API, runs
 * the real onload() and the real action clicks, and asserts the behaviours the
 * plugin exists for (v6.0.0, the simplified final version):
 *   - the mineplus format registers with animation_mode + display_mode, so the
 *     Animate tab and the Display tab both exist for new and converted models;
 *   - converting an old model keeps cubes/bones/textures/display/animations
 *     and switches the GLOBAL format (what the tabs actually follow);
 *   - the display panel repair enters display mode and loads a slot, so the
 *     default Display editor is never empty;
 *   - everything else from v5.x (custom editors, validators, templates,
 *     toggles, reports) is gone on purpose.
 *
 * The sandbox mirrors the REAL Blockbench globals: the open project is
 * `Project` (and Blockbench.Project), and there is no `Model` global — that
 * mismatch was the v4.1 bug where convert silently did nothing on old models.
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

// `display_presets` mirrors the real fixed preset list: Blockbench's Java
// Block/Item template gets its display defaults from the fixed `item` preset,
// applied to ALL slots through DisplayMode.applyPreset(preset, true).
const sandbox = {
  display_presets: [
    { id: 'block', fixed: true, areas: {} },
    { id: 'item', fixed: true, areas: { ground: { rotation: [0, 0, 0] } } }
  ],
  console: { log: log, warn: log, error: log },
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
  Codecs: { project: { load_filter: { extensions: ['bbmodel'] } } },
  Canvas: { updateAll: () => {}, updateViewMode: () => {} },
  Mode: { edit: {}, select: () => {} },
  Modes: { display: { id: 'display', select() { this.selected = true; } } }
};
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

// 1. onload registers exactly the two tools plus the mpmodel format.
sandbox.__plugin.onload();
const toolIds = menuBar.tools.map(a => a.id);
check('onload runs without throwing', toolIds.length > 0);
check('exactly two menu actions (convert + open&convert)',
  toolIds.length === 2 && toolIds.includes('mineplus_convert')
    && toolIds.includes('mineplus_open_convert'));
const fmt = registeredFormats.mineplus_model;
check('mineplus_model format registered', !!fmt);
check('format enables animation mode (Animate tab exists)', fmt && fmt.animation_mode === true);
check('format enables display mode', fmt && fmt.display_mode === true);
check('.mpmodel bound to the project codec (save/load via File menu)',
  fmt && fmt.codec === sandbox.Codecs.project);
check('.mpmodel added to the project codec load filter',
  sandbox.Codecs.project.load_filter.extensions.includes('mpmodel'));

// 2. Conversion of an old-format model keeps all content and switches format.
// REGRESSION (v4.2.0): the sandbox has the real Blockbench globals — `Project`
// only, no `Model` — and conversion must still work end to end. DisplayMode
// exists in the real app from startup; convert drops the designer into
// display mode via repairDisplayPanel.
sandbox.DisplayMode = {
  display_slot: null, vue: null, load() {},
  presetCalls: [],
  applyPreset(preset, all) { this.presetCalls.push({ id: preset && preset.id, all: all === true }); }
};
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
check('display block kept (1 context — vanilla defaults apply elsewhere)',
  Object.keys(project.display).length === 1);
check('no metadata stamp written (plain bbmodel content only)', project.mineplus === undefined);
// v6.1: a model without display settings gets the built-in Java-item preset
// on ALL slots — the same mechanism as the default Java Block/Item template.
check('built-in Java-item display preset applied to all slots',
  sandbox.DisplayMode.presetCalls.length === 1
    && sandbox.DisplayMode.presetCalls[0].id === 'item'
    && sandbox.DisplayMode.presetCalls[0].all === true);

// 2z. A model with AUTHORED display settings must keep them untouched.
sandbox.Project = makeProject();
sandbox.Project.display_settings = { thirdperson_righthand: { rotation: [10, 20, 30] } };
sandbox.DisplayMode.presetCalls = [];
actions.mineplus_convert.click();
check('authored display settings are not overwritten by the preset',
  sandbox.DisplayMode.presetCalls.length === 0
    && sandbox.Project.display_settings.thirdperson_righthand.rotation[0] === 10);

// 2a. When the old format is still the active global and Blockbench's native
// ModelFormat.convertTo() is available, it is used — it also migrates box UV,
// bone-rig grouping and rotation limits, which old .bbmodel files need.
sandbox.Project = makeProject();
sandbox.Blockbench.Format = { id: 'bb_model' };
let nativeUsed = false;
fmt.convertTo = function () { nativeUsed = true; this.select(); };
actions.mineplus_convert.click();
check('convert uses native convertTo() when available',
  nativeUsed && sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model');

// 2b. …and conversion still succeeds when native convertTo throws.
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

// 2c. REGRESSION (v5.0): native convertTo() is used even when the global
// format is unset (fresh Blockbench state).
sandbox.Project = makeProject();
sandbox.Blockbench.Format = null;
let nativeUsedUnset = false;
fmt.convertTo = function () { nativeUsedUnset = true; this.select(); };
actions.mineplus_convert.click();
check('native convertTo() used even when global Format is unset',
  nativeUsedUnset && sandbox.Project.format && sandbox.Project.format.id === 'mineplus_model');

// 2d. REGRESSION (v5.0): if native convertTo() runs but does not update the
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

// 3. Converting an already-mineplus project is a no-op that still succeeds.
actions.mineplus_convert.click();
check('second convert is idempotent', project.format.id === 'mineplus_model'
  && project.elements.length === 3 && Object.keys(project.animations).length === 2);

// 4. Reopened mineplus project: even when format is already mineplus, the
// global format (which drives the Display/Animate tabs) gets re-selected.
sandbox.Project = makeProject();
sandbox.Project.format = fmt;         // model says mineplus…
sandbox.__selectedFormat = null;      // …but the global format is stale
actions.mineplus_convert.click();
check('reopened model re-selects global format (Display tab fix)',
  sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 5. The project_loaded hook runs the auto-apply (logged, not silently caught).
const project2 = makeProject();
sandbox.Project = project2;
sandbox.__selectedFormat = null;
(hooks.project_loaded || []).forEach(h => h());
check('project_loaded hook auto-applies mineplus format',
  project2.format && project2.format.id === 'mineplus_model'
    && sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 6. REGRESSION (v5.1): the Display panel only renders its slot list and
// sliders while display MODE is active (condition {modes: ['display']}); the
// repair must enter display mode itself and load the default slot.
sandbox.Modes = {
  options: { display: { id: 'display', select() { sandbox.Modes.displaySelected = true; } } },
  displaySelected: false
};
sandbox.Mode = { id: 'edit' };
// Real Blockbench keeps the active format as a page-scope global.
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
// When the mode switch fails, the repair must not throw.
delete sandbox.Modes;
sandbox.Modes = { display: { select() { throw new Error('nope'); } } };
let repairFail = null;
try { (hooks.select_mode || []).forEach(h => h({ mode: { id: 'edit' } })); }
catch (e) { repairFail = e; }
check('display panel repair failure is caught and logged, not thrown',
  repairFail === null);
delete sandbox.DisplayMode;
delete sandbox.Interface;

// 7. The v5.x extras are gone on purpose (simplified final version):
// no custom editor, validators, template tools, reports, toggles or Studio.
['mineplus_display_editor', 'mineplus_copy_display', 'mineplus_validate_display',
  'mineplus_validate_model', 'mineplus_template_settings', 'mineplus_check_template',
  'mineplus_save_mpmodel', 'mineplus_export_bbmodel', 'mineplus_open_generic',
  'mineplus_fix_duplicates', 'mineplus_model_report', 'mineplus_show_log',
  'mineplus_toggle_tools', 'mineplus_open_migrate', 'mineplus_studio'
].forEach(id => check('removed tool stays removed: ' + id, actions[id] === undefined));

// 8. onunload removes the actions and hooks.
sandbox.__plugin.onunload();
check('onunload removes the actions', actions.mineplus_convert === undefined
  && actions.mineplus_open_convert === undefined);
check('onunload removes the hooks', Object.keys(hooks).every(k => hooks[k].length === 0));

console.log(failures === 0
  ? '\nAll checks passed.'
  : `\n${failures} check(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
