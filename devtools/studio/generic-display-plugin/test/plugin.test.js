/* Headless tests for mineplus_generic_display.js — run with `node test/plugin.test.js`.
 *
 * Loads the plugin source in a vm sandbox with a stubbed Blockbench API, runs
 * the real onload() and the real action clicks, and asserts the behaviours
 * added in v3.2: tools toggle, mineplus format with animation support, and the
 * old-model conversion that keeps cubes/bones/textures/display/animations.
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
    mesh: true,
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
    // like Blockbench's real Format.select(): updates the active project's
    // format via the global selector, not a direct assignment
    opts.select = function () {
      sandbox.__selectedFormat = this;
      if (sandbox.Model) sandbox.Model.format = this;
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
    sandbox.Dialog.last = this;
  },
  Cube: FakeCube,
  Group: FakeGroup
};
sandbox.Panel.stored = {};
sandbox.Dialog.last = null;
sandbox.Dialog.prototype.show = function () { this.shown = true; };
sandbox.Panel.prototype.delete = function () { delete sandbox.Panel.stored[this.id]; };

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
sandbox.Model = makeProject();
actions.mineplus_convert.click();
const project = sandbox.Model;
check('converted to mineplus format', project.format && project.format.id === 'mineplus_model');
check('format switched via Format.select() (Display tab global updated)', sandbox.__selectedFormat
  && sandbox.__selectedFormat.id === 'mineplus_model');
check('cubes kept (3)', project.elements.length === 3);
check('bones kept (2, incl. non-exported)', project.groups.length === 2);
check('textures kept (1)', project.textures.length === 1);
check('animations kept (2 — Animate tab content survives)', Object.keys(project.animations).length === 2);
check('display block kept (1 context)', Object.keys(project.display).length === 1);
check('template stamp added', project.mineplus && project.mineplus.plugin === 'mineplus_generic_display');

// 4. Converting an already-mineplus project is a no-op that still succeeds.
actions.mineplus_convert.click();
check('second convert is idempotent', project.format.id === 'mineplus_model'
  && project.elements.length === 3 && Object.keys(project.animations).length === 2);

// 5b. Reopened mineplus project: even when format is already mineplus, the
// global format (which drives the Display/Animate tabs) gets re-selected —
// the "display but no Animate/Display tab" bug on old models.
sandbox.Model = makeProject();
sandbox.Model.format = fmt;           // model says mineplus…
sandbox.__selectedFormat = null;      // …but the global format is stale
actions.mineplus_convert.click();
check('reopened model re-selects global format (Display tab fix)',
  sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 5c. The project_loaded hook runs the auto-apply (logged, not silently caught).
const project2 = makeProject();
sandbox.Model = project2;
sandbox.__selectedFormat = null;
(hooks.project_loaded || []).forEach(h => h());
check('project_loaded hook auto-applies mineplus format',
  project2.format && project2.format.id === 'mineplus_model'
    && sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');

// 6. Studio: opens the DEFAULT Blockbench UI — mineplus format selected,
// display mode entered, and the default Display/Animations panels brought
// forward. No custom GUI.
sandbox.Panel.stored.display = { select() { this.selected = true; } };
sandbox.Panel.stored.animations = { select() { this.selected = true; } };
sandbox.Modes.display.selected = false;
sandbox.__selectedFormat = null;
actions.mineplus_studio.click();
check('studio action registered', toolIds.includes('mineplus_studio'));
check('studio re-selects the mineplus format',
  sandbox.__selectedFormat && sandbox.__selectedFormat.id === 'mineplus_model');
check('studio enters display mode', sandbox.Modes.display.selected === true);
check('studio opens the default Display panel', sandbox.Panel.stored.display.selected === true);
check('studio opens the default Animations panel', sandbox.Panel.stored.animations.selected === true);

// 6b. onunload removes the actions.
sandbox.__plugin.onunload();
check('onunload runs cleanly', actions.mineplus_display_editor === undefined);

console.log(failures === 0
  ? `\nAll ${'checks'} passed.`
  : `\n${failures} check(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
