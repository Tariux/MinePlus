/* Mineplus Generic Display — Blockbench plugin v6.1.0.
 *
 * One job: after converting an old bbmodel — or creating a brand-new model —
 * the Animate tab AND the Display tab (with the default Java-item slot list
 * and rotation/translation/scale sliders) are both available, using nothing
 * but Blockbench's built-in tools. No custom GUI, no extra editors.
 *
 * Tools menu (2 items):
 *   - Mineplus: Convert to Mineplus Model
 *       switch the OPEN model to the mineplus format. Cubes, bones, textures,
 *       the authored display block and every animation are kept untouched —
 *       only the format changes, so both tabs work. Afterwards you land in
 *       Blockbench's own display mode.
 *   - Mineplus: Open & Convert Old Model…
 *       pick a .bbmodel / .json / .mpmodel file; it is opened through the
 *       matching codec and converted in one step.
 *
 * New models: the plugin registers the "Mineplus Model" format, so it appears
 * in Blockbench's normal New Model format list — picking it gives a fresh
 * model with both tabs from the start (animation_mode + display_mode on).
 *
 * Formats: `mineplus_model` (.mpmodel) is bound to the built-in project codec
 * (Codecs.project), so .mpmodel saves, loads and re-saves exactly like a
 * .bbmodel through Blockbench's own File menu. The Mine Plus server plugin
 * reads both with display and animations.
 */
(function () {
  'use strict';

  var PLUGIN_ID = 'mineplus_generic_display';
  var LOG_PREFIX = '[Mineplus]';
  var MP_FORMAT_ID = 'mineplus_model';

  var state = { actions: [], hooks: [], addedExtension: false };

  /* ------------------------------------------------------------------ log */

  function log(message) {
    console.log(LOG_PREFIX + ' ' + message);
  }

  function warn(message) {
    console.warn(LOG_PREFIX + ' ' + message);
  }

  /* --------------------------------------------------------- model helpers */

  // The open project is the global `Project` (mirrored on Blockbench.Project);
  // Blockbench.Project is 0 when nothing is open, so require a uuid.
  function currentModel() {
    if (typeof Project !== 'undefined' && Project && Project.uuid) return Project;
    if (typeof Blockbench !== 'undefined' && Blockbench.Project
        && Blockbench.Project.uuid) return Blockbench.Project;
    return null;
  }

  function displayBlock(model) {
    var m = model || currentModel();
    if (!m) return {};
    if (!m.display || typeof m.display !== 'object') m.display = {};
    return m.display;
  }

  function contentCounts(model) {
    return {
      cubes: (model.elements || []).length,
      bones: (model.groups || []).length,
      textures: (model.textures || []).length,
      animations: model.animations ? Object.keys(model.animations).length : 0,
      display: Object.keys(displayBlock(model)).length
    };
  }

  /* --------------------------------------------------------------- format */

  function allFormats() {
    // `ModelFormat.all` does not exist in every Blockbench build (5.2.2
    // desktop ships it undefined); fall back to the legacy `Formats` map.
    if (typeof ModelFormat !== 'undefined' && Array.isArray(ModelFormat.all)) {
      return ModelFormat.all;
    }
    if (typeof Formats !== 'undefined' && Formats) {
      return Object.keys(Formats).map(function (k) { return Formats[k]; });
    }
    warn('no format registry found (ModelFormat.all and Formats both missing)');
    return [];
  }

  function registerFormat() {
    if (allFormats().some(function (f) { return f && f.id === MP_FORMAT_ID; })) {
      log('format ' + MP_FORMAT_ID + ' already registered; skipping');
      return;
    }
    if (typeof ModelFormat !== 'function') {
      warn('ModelFormat constructor missing in this Blockbench build;'
          + ' .mpmodel registration skipped');
      return;
    }
    // animation_mode + display_mode both true is what makes Blockbench show
    // the Animate tab AND the Display tab for this format — nothing else
    // controls tab visibility.
    new ModelFormat({
      id: MP_FORMAT_ID,
      name: 'Mineplus Model',
      description: 'A standard bbmodel saved with the .mpmodel extension.'
          + ' Full animation and display support.',
      extension: 'mpmodel',
      icon: 'icon-format_free',
      model_identifier: 'model',
      vertex_color: false,
      texture_meshes: false,
      animation_mode: true,
      animation_files: false,
      display_mode: true,
      bone_rig: true,
      centered_grid: true
    });
    // .mpmodel is a full bbmodel: bind the built-in project codec so it
    // saves, loads and re-saves through Blockbench's own File menu.
    if (typeof Codecs !== 'undefined' && Codecs.project && Codecs.project.load_filter) {
      var fmt = allFormats().filter(function (f) { return f && f.id === MP_FORMAT_ID; })[0];
      fmt.codec = Codecs.project;
      fmt.animation_codec = Codecs.project.animation_codec;
      var exts = Codecs.project.load_filter.extensions;
      if (exts.indexOf('mpmodel') < 0) {
        exts.push('mpmodel');
        state.addedExtension = true;
      }
      log('format ' + MP_FORMAT_ID + ' registered (.mpmodel), bound to the'
          + ' project codec');
    } else {
      warn('format ' + MP_FORMAT_ID + ' registered, but Codecs.project is'
          + ' unavailable — .mpmodel has no codec in this build');
    }
  }

  /* ------------------------------------------------------------ conversion */

  // The global active format — the thing the Animate/Display tabs actually
  // follow (page-scope `Format` var, mirrored on Blockbench.Format). Never
  // trust model.format alone for tab visibility.
  function globalFormat() {
    try {
      // eslint-disable-next-line no-undef
      if (typeof Format !== 'undefined' && Format) return Format;
    } catch (ignored) { /* page-scope var not reachable */ }
    if (typeof Blockbench !== 'undefined' && Blockbench.Format) {
      return Blockbench.Format;
    }
    return null;
  }

  // Make `fmt` the ACTIVE format everywhere: fmt.select() is the official
  // switch (updates the global Format, panels and mode bar); the codec and
  // direct-assignment paths cover builds where select() is missing or throws.
  // A bare `model.format = fmt` alone leaves the global stale — Animate stays
  // from the old format and the Display tab never appears.
  function selectMineplusFormat(fmt, model) {
    if (typeof fmt.select === 'function') {
      try {
        fmt.select();
        return true;
      } catch (failure) {
        warn('fmt.select() threw: ' + (failure && failure.message));
      }
    }
    if (fmt.codec && typeof fmt.codec.select === 'function') {
      try {
        fmt.codec.select();
        return true;
      } catch (failure) {
        warn('fmt.codec.select() threw: ' + (failure && failure.message));
      }
    }
    try {
      // Plugins share the page scope: `Format` is Blockbench's global active
      // format (a `var` at top level of a classic script, reachable here).
      // eslint-disable-next-line no-undef
      Format = fmt;
    } catch (failure) {
      warn('global Format assignment failed: ' + (failure && failure.message));
    }
    if (model && typeof model === 'object') model.format = fmt;
    try {
      if (typeof updateModeSelector === 'function') updateModeSelector();
      else if (typeof Blockbench !== 'undefined' && Blockbench.dispatchEvent) {
        Blockbench.dispatchEvent('update_mode_selector');
      }
    } catch (failure) {
      warn('mode selector refresh failed: ' + (failure && failure.message));
    }
    return false;
  }

  // Switch the open project to the mineplus format. Returns true on success.
  function applyMineplusFormat(auto) {
    var model = currentModel();
    if (!model) return false;
    var fmt = allFormats().filter(function (f) { return f && f.id === MP_FORMAT_ID; })[0];
    if (!fmt) {
      if (!auto) warn('convert: mineplus format not registered in this build');
      return false;
    }
    if (model.format && model.format.id === MP_FORMAT_ID) {
      // Still re-select the global format: after a load the global `Format`
      // may not point at mineplus yet, and the tabs follow the GLOBAL format.
      selectMineplusFormat(fmt, model);
      log('format already ' + MP_FORMAT_ID + ' — global selection re-applied');
    } else {
      log('format switch: ' + (model.format && model.format.id ? model.format.id
          : '(unknown)') + ' → ' + MP_FORMAT_ID);
      // Preferred path: Blockbench's native ModelFormat.convertTo() — it also
      // migrates box UV, bone rig grouping (loose cubes get a root group) and
      // rotation limits, which old .bbmodel files saved in generic/java
      // formats need and a bare select() skips.
      if (typeof fmt.convertTo === 'function') {
        try {
          fmt.convertTo();
          log('format converted via native convertTo()');
        } catch (failure) {
          warn('native convertTo() threw: ' + (failure && failure.message)
              + ' — falling back to plain select');
          selectMineplusFormat(fmt, model);
        }
      } else {
        selectMineplusFormat(fmt, model);
      }
      // Verify the GLOBAL format actually landed on mineplus (the tabs are
      // gated by Format.animation_mode and Format.display_mode). Old bbmodel
      // files load in the generic 'free' format, which sets animation_mode
      // but NOT display_mode — that is the "Animate exists, no Display tab"
      // bug. If the native path did not land, fall back to select().
      var global = globalFormat();
      if (!global || global.id !== MP_FORMAT_ID) {
        warn('convert: global Format did not land on ' + MP_FORMAT_ID
            + ' — re-selecting');
        selectMineplusFormat(fmt, model);
      }
    }
    var landed = globalFormat();
    if (landed && landed.display_mode === true && landed.animation_mode === true) {
      log('tabs after convert: Animate ON, Display ON');
    } else {
      warn('tab check after convert: global format '
          + (landed && landed.id ? landed.id : '(none)')
          + ' animation_mode=' + !!(landed && landed.animation_mode)
          + ' display_mode=' + !!(landed && landed.display_mode));
    }
    // Fill the Display panel the way Blockbench's own Java Block/Item template
    // does: its fixed `item` display preset (js/display_mode/display_mode.js,
    // `display_presets`) applied to ALL slots through the built-in
    // DisplayMode.applyPreset(.., true). Only when the model carries no
    // authored display settings, so real display data is never overwritten.
    applyJavaItemDisplayDefaults();
    // Drop the designer into Blockbench's own display mode so the Display
    // panel (which only renders in display mode) shows the default slot list
    // and sliders immediately.
    repairDisplayPanel();
    if (typeof Canvas !== 'undefined' && Canvas.updateAll) Canvas.updateAll();
    return true;
  }

  // Blockbench's Java Block/Item template gets its display defaults from the
  // fixed `item` entry in `display_presets` (js/display_mode/display_mode.js),
  // applied to every slot at once via DisplayMode.applyPreset(preset, true).
  // This reuses exactly that built-in preset and API — no custom values — and
  // only fills models that carry no display settings at all.
  function applyJavaItemDisplayDefaults() {
    var model = currentModel();
    if (!model) return false;
    if (model.display_settings && Object.keys(model.display_settings).length > 0) {
      log('display settings present — leaving authored values untouched');
      return false;
    }
    if (typeof DisplayMode === 'undefined' || !DisplayMode
        || typeof DisplayMode.applyPreset !== 'function') {
      warn('DisplayMode.applyPreset unavailable — skipping display defaults');
      return false;
    }
    var itemPreset = null;
    if (typeof display_presets !== 'undefined' && display_presets
        && typeof display_presets.forEach === 'function') {
      display_presets.forEach(function (preset) {
        if (preset && preset.id === 'item') itemPreset = preset;
      });
    }
    if (!itemPreset) {
      warn('built-in Java-item display preset not found — skipping');
      return false;
    }
    try {
      DisplayMode.applyPreset(itemPreset, true);
      log('vanilla Java-item display preset applied to all slots');
      return true;
    } catch (failure) {
      warn('applyPreset threw: ' + (failure && failure.message));
      return false;
    }
  }

  // The Display panel only renders its slot list and sliders while display
  // MODE is active (condition {modes: ['display']}); entering the mode and
  // (re)binding the panel is all the repair the default UI ever needs.
  function repairDisplayPanel() {
    var modes = typeof Modes !== 'undefined' ? Modes : null;
    var displayMode = modes && (modes.display
        || (modes.options && modes.options.display));
    var inDisplayMode = (typeof Mode !== 'undefined' && Mode && Mode.id === 'display');
    try {
      if (displayMode && typeof displayMode.select === 'function' && !inDisplayMode) {
        displayMode.select();
        log('display mode entered — default display editor is shown');
      }
    } catch (failure) {
      warn('entering display mode failed: ' + (failure && failure.message));
    }
    if (typeof DisplayMode === 'undefined' || !DisplayMode) return;
    try {
      if (!DisplayMode.vue && typeof Interface !== 'undefined' && Interface.Panels
          && Interface.Panels.display) {
        DisplayMode.vue = Interface.Panels.display.inside_vue;
        log('display panel vue bound (was missing)');
      }
    } catch (failure) {
      warn('display panel vue binding failed: ' + (failure && failure.message));
    }
    try {
      var slot = DisplayMode.display_slot || 'thirdperson_righthand';
      var bound = DisplayMode.vue;
      var hasSlot = bound && bound._data && bound._data.slot
          && bound._data.slot.slot_id;
      if (!hasSlot && typeof DisplayMode.load === 'function') {
        DisplayMode.load(slot);
        log('display slot "' + slot + '" loaded — panel sliders restored');
      }
    } catch (failure) {
      warn('display slot re-load failed: ' + (failure && failure.message));
    }
  }

  function convertToMineplus() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var before = contentCounts(model);
    var ok = applyMineplusFormat(false);
    if (!ok) {
      Blockbench.showQuickMessage('Mineplus format unavailable — see console log');
      return;
    }
    var after = contentCounts(model);
    var lost = Object.keys(before).filter(function (k) { return after[k] < before[k]; });
    if (lost.length) {
      warn('convert: some content count dropped: before ' + JSON.stringify(before)
          + ' after ' + JSON.stringify(after) + ' — undo (Ctrl+Z) and report the log');
      Blockbench.showQuickMessage('Converted with content changes — see console log');
    } else {
      log('convert: content intact ' + JSON.stringify(after));
      Blockbench.showQuickMessage('Converted to Mineplus Model'
          + ' — Animate and Display tabs enabled');
    }
  }

  // Open an old .bbmodel (or any project file) and convert it in one step.
  function openAndConvert() {
    if (typeof loadModelFile !== 'function') {
      warn('loadModelFile missing in this build; open the file with File > Open'
          + ' Model, then run Convert');
      Blockbench.showQuickMessage('Open the file first, then run Convert to Mineplus Model');
      return;
    }
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = '.bbmodel,.bbmodel.old,.json,.mpmodel';
    input.onchange = function () {
      var file = input.files && input.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        log('opening "' + file.name + '" through its matching codec');
        try {
          loadModelFile({ content: reader.result, path: file.name, name: file.name }, {});
          log('loaded — converting to Mineplus Model');
          setTimeout(function () { convertToMineplus(); }, 50);
        } catch (failure) {
          warn('open failed: ' + (failure && failure.message));
        }
      };
      reader.onerror = function () { warn('file read failed for ' + file.name); };
      reader.readAsText(file);
    };
    input.click();
  }

  /* ----------------------------------------------------------------- setup */

  function hook(event, handler) {
    try {
      Blockbench.on(event, handler);
      state.hooks.push({ event: event, handler: handler });
    } catch (failure) {
      warn('hook ' + event + ' could not register: '
          + (failure && failure.message));
    }
  }

  Plugin.register(PLUGIN_ID, {
    title: 'Mineplus Generic Display',
    author: 'Mineplus',
    version: '6.1.0',
    min_version: '4.0.0',
    tags: ['mineplus', 'display', 'animation', 'minecraft', 'mpmodel'],
    onload: function () {
      log('onload: starting (v6.1.0)');
      registerFormat();

      var convert = new Action('mineplus_convert', {
        name: 'Mineplus: Convert to Mineplus Model',
        icon: 'icon-change_format',
        category: 'edit',
        click: convertToMineplus
      });
      MenuBar.addAction(convert, 'tools');
      state.actions.push(convert);

      var openConvert = new Action('mineplus_open_convert', {
        name: 'Mineplus: Open & Convert Old Model…',
        icon: 'icon-open_folder',
        category: 'edit',
        click: openAndConvert
      });
      MenuBar.addAction(openConvert, 'tools');
      state.actions.push(openConvert);

      log('onload: 2 actions registered under Tools');

      // After any load, make sure the mineplus format is active so both tabs
      // are available without a manual step. Load events differ across
      // versions; register all of them.
      function autoApply() {
        try { applyMineplusFormat(true); }
        catch (failure) { warn('auto format apply threw: ' + (failure && failure.message)); }
      }
      hook('load_project', autoApply);
      hook('load_project', function () { setTimeout(autoApply, 50); });
      hook('project_loaded', autoApply);
      hook('select_project', autoApply);
      // Blockbench dispatches `select_mode` when a mode tab is clicked
      // (js/modes.ts:139); re-bind the display panel each time display mode
      // is entered, so it is never empty.
      hook('select_mode', function (data) {
        var mode = data && data.mode;
        if (mode && mode.id === 'display') repairDisplayPanel();
      });
    },
    onunload: function () {
      state.actions.forEach(function (action) {
        try { MenuBar.removeAction(action); } catch (e) { /* already gone */ }
      });
      state.hooks.forEach(function (h) {
        try { Blockbench.off(h.event, h.handler); } catch (e) { /* already gone */ }
      });
      if (state.addedExtension && typeof Codecs !== 'undefined' && Codecs.project
          && Codecs.project.load_filter && Codecs.project.load_filter.extensions) {
        var index = Codecs.project.load_filter.extensions.indexOf('mpmodel');
        if (index >= 0) Codecs.project.load_filter.extensions.splice(index, 1);
      }
      state.actions = [];
      state.hooks = [];
      state.addedExtension = false;
      log('onunload: actions, hooks and .mpmodel extension removed');
    },
    oninstall: function () { log('oninstall'); },
    onenabled: function () { log('onenabled'); },
    ondisabled: function () { log('ondisabled'); }
  });
})();
