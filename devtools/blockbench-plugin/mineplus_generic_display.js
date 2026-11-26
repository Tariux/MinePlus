/* Mineplus Generic Display — Blockbench plugin v6.2.0.
 *
 * One job: after converting an old bbmodel — or creating a brand-new model —
 * the Animate tab AND the Display tab (with the default Java-item slot list
 * and rotation/translation/scale sliders) are both available, using nothing
 * but Blockbench's built-in tools. No custom GUI, no extra editors.
 *
 * Tools menu (4 items):
 *   - Mineplus: Convert to Mineplus Model
 *       switch the OPEN model to the mineplus format. Cubes, bones, textures,
 *       the authored display block and every animation are kept untouched —
 *       only the format changes, so both tabs work. Afterwards you land in
 *       Blockbench's own display mode.
 *   - Mineplus: Open & Convert Old Model…
 *       pick a .bbmodel / .json / .mpmodel file; it is opened through the
 *       matching codec and converted in one step.
 *   - Mineplus: Copy Display Settings
 *       snapshot the OPEN model's complete Display tab settings — every slot
 *       (thirdperson/firstperson/head/ground/gui/fixed/…): rotation,
 *       translation, scale, pivots, mirror — through the built-in
 *       DisplaySlot.copy() path. Kept in memory and put on the system
 *       clipboard as marked JSON, so it survives closing the model.
 *   - Mineplus: Paste Display Settings
 *       apply the copied snapshot to the OPEN model slot by slot through the
 *       built-in DisplaySlot.extend() path (the same call Blockbench's own
 *       preset apply uses), with undo support. Values land exactly as
 *       authored — nothing is recomputed or overridden.
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

  var state = { actions: [], hooks: [], addedExtension: false, copiedDisplay: null };

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

  /* ------------------------------------------------- display copy / paste */

  // Canonical Display tab slots (js/display_mode/display_references.js plus
  // the classic Java-item slots). DisplayMode.slots is preferred when the
  // running build exposes it, so new slots are picked up automatically.
  var FALLBACK_SLOTS = ['thirdperson_righthand', 'thirdperson_lefthand',
    'firstperson_righthand', 'firstperson_lefthand', 'head', 'ground',
    'gui', 'fixed', 'embedded', 'on_shelf'];

  function knownSlots(model) {
    var slots = [];
    function push(s) {
      s = String(s);
      if (s && slots.indexOf(s) < 0) slots.push(s);
    }
    try {
      if (typeof DisplayMode !== 'undefined' && DisplayMode
          && Array.isArray(DisplayMode.slots)) {
        DisplayMode.slots.forEach(push);
      }
    } catch (failure) {
      warn('DisplayMode.slots unreadable: ' + (failure && failure.message));
    }
    FALLBACK_SLOTS.forEach(push);
    var ds = model && model.display_settings;
    if (ds && typeof ds === 'object') Object.keys(ds).forEach(push);
    return slots;
  }

  // Plain-data snapshot of one slot. DisplaySlot.copy() (the built-in full
  // copy: rotation, translation, scale, pivots, mirror, fit_to_frame) is the
  // preferred path; the plain fallback reads the same fields so it works on
  // raw bbmodel `display` objects too.
  function snapshotSlot(slot) {
    if (!slot || typeof slot !== 'object') return null;
    if (typeof slot.copy === 'function') {
      try {
        var copied = slot.copy();
        if (copied && typeof copied === 'object') return copied;
      } catch (failure) {
        warn('slot copy() threw: ' + (failure && failure.message));
      }
    }
    var out = {};
    ['rotation', 'translation', 'scale', 'rotation_pivot', 'scale_pivot',
      'mirror'].forEach(function (k) {
      if (Array.isArray(slot[k])) out[k] = slot[k].slice();
    });
    if (typeof slot.fit_to_frame === 'boolean') out.fit_to_frame = slot.fit_to_frame;
    return Object.keys(out).length ? out : null;
  }

  function collectDisplaySnapshot(model) {
    var slots = knownSlots(model);
    var ds = model.display_settings;
    var raw = (!ds || typeof ds !== 'object' || Object.keys(ds).length === 0)
        ? (model.display || null) : ds;   // raw bbmodel display block fallback
    if (!raw || typeof raw !== 'object') return {};
    var out = {};
    slots.forEach(function (sl) {
      var snap = raw[sl] ? snapshotSlot(raw[sl]) : null;
      if (snap) out[sl] = snap;
    });
    return out;
  }

  var MARKER = 'mineplus_display_copy';
  function clipboardPayload(model, slots) {
    return JSON.stringify({
      mineplus_display_copy: 1,
      version: 1,
      source_model: (model && model.name) || 'model',
      slots: slots
    });
  }
  function parseClipboardPayload(text) {
    if (typeof text !== 'string' || text.indexOf(MARKER) < 0) return null;
    try {
      var data = JSON.parse(text);
      if (data && data[MARKER] === 1 && data.slots
          && typeof data.slots === 'object' && Object.keys(data.slots).length) {
        return data;
      }
    } catch (failure) {
      warn('clipboard display payload unparsable: ' + (failure && failure.message));
    }
    return null;
  }

  function copyDisplaySettings() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var slots = collectDisplaySnapshot(model);
    if (!Object.keys(slots).length) {
      log('copy: model has no display settings — nothing to copy');
      Blockbench.showQuickMessage('No display settings on this model — nothing to copy');
      return;
    }
    state.copiedDisplay = { source_model: model.name || 'model', slots: slots };
    var payload = clipboardPayload(model, slots);
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard
          && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(payload);
        log('copy: ' + Object.keys(slots).length + ' slot(s) copied'
            + ' to clipboard and memory');
      } else {
        log('copy: ' + Object.keys(slots).length + ' slot(s) copied'
            + ' to memory (system clipboard unavailable)');
      }
    } catch (failure) {
      warn('clipboard write failed (memory copy kept): '
          + (failure && failure.message));
    }
    Blockbench.showQuickMessage('Display settings copied ('
        + Object.keys(slots).length + ' slots) — open the target model and paste');
  }

  // Merge one pasted slot the same way DisplayMode.applyPreset does: build a
  // fresh DisplaySlot when the target has none, extend() it with the snapshot
  // (extend normalises degrees, mirror-from-negative-scale, pivots), and
  // reset pivots the snapshot does not carry — identical to preset semantics.
  function pasteSlotData(model, slotId, data) {
    if (!model.display_settings || typeof model.display_settings !== 'object') {
      model.display_settings = {};
    }
    var target = model.display_settings[slotId];
    if (!target || typeof target !== 'object') {
      if (typeof DisplaySlot === 'function') {
        target = new DisplaySlot(slotId);
      } else {
        target = { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] };
      }
      model.display_settings[slotId] = target;
    }
    if (typeof target.extend === 'function') {
      target.extend(data);
    } else {
      ['rotation', 'translation', 'scale', 'rotation_pivot', 'scale_pivot',
        'mirror'].forEach(function (k) {
        if (Array.isArray(data[k])) target[k] = data[k].slice();
      });
      if (typeof data.fit_to_frame === 'boolean') target.fit_to_frame = data.fit_to_frame;
    }
    ['rotation_pivot', 'scale_pivot'].forEach(function (pivot) {
      if (!Array.isArray(data[pivot])) {
        var current = target[pivot];
        if (current && typeof current.replace === 'function') current.replace([0, 0, 0]);
        else if (Array.isArray(current)) target[pivot] = [0, 0, 0];
        else target[pivot] = [0, 0, 0];
      }
    });
  }

  function pasteDisplaySettings() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var payload = state.copiedDisplay || null;
    if (!payload && typeof navigator !== 'undefined' && navigator.clipboard
        && navigator.clipboard.readText) {
      // readText is async and needs document focus; the in-memory copy is the
      // primary path — the clipboard is only a bonus across sessions.
      warn('paste: no in-memory copy — use Copy Display Settings in this'
          + ' Blockbench session (clipboard paste-back is not supported here)');
    }
    if (!payload) {
      Blockbench.showQuickMessage('Nothing to paste — copy display settings first');
      return;
    }
    var allowed = knownSlots(model);
    var applied = [];
    try {
      if (typeof Undo !== 'undefined' && Undo && Undo.initEdit && Undo.finishEdit) {
        Undo.initEdit({ display_slots: Object.keys(payload.slots).filter(
            function (s) { return allowed.indexOf(s) >= 0; }) });
      }
    } catch (failure) {
      warn('undo init failed (paste continues): ' + (failure && failure.message));
    }
    Object.keys(payload.slots).forEach(function (slotId) {
      if (allowed.indexOf(slotId) < 0) {
        warn('paste: skipping unknown slot "' + slotId + '"');
        return;
      }
      var snap = payload.slots[slotId];
      if (!snap || typeof snap !== 'object') return;
      pasteSlotData(model, slotId, snap);
      applied.push(slotId);
    });
    if (!applied.length) {
      Blockbench.showQuickMessage('Nothing to paste — no compatible slots');
      return;
    }
    try {
      if (typeof Undo !== 'undefined' && Undo && Undo.finishEdit) {
        Undo.finishEdit('Paste display settings');
      }
    } catch (failure) {
      warn('undo finish failed (paste kept): ' + (failure && failure.message));
    }
    try {
      if (typeof Mode !== 'undefined' && Mode && Mode.id === 'display'
          && typeof DisplayMode !== 'undefined' && DisplayMode
          && typeof DisplayMode.load === 'function') {
        DisplayMode.load(DisplayMode.display_slot || 'thirdperson_righthand');
      } else if (typeof Canvas !== 'undefined' && Canvas.updateAll) {
        Canvas.updateAll();
      }
    } catch (failure) {
      warn('post-paste refresh failed: ' + (failure && failure.message));
    }
    log('paste: applied ' + applied.length + ' slot(s) from "'
        + (payload.source_model || 'model') + '"');
    Blockbench.showQuickMessage('Display settings pasted (' + applied.length
        + ' slots) — press Ctrl+Z to undo');
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
    version: '6.2.0',
    min_version: '4.0.0',
    tags: ['mineplus', 'display', 'animation', 'minecraft', 'mpmodel'],
    onload: function () {
      log('onload: starting (v6.2.0)');
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

      var copyDisplay = new Action('mineplus_copy_display', {
        name: 'Mineplus: Copy Display Settings',
        icon: 'icon-copy',
        category: 'edit',
        click: copyDisplaySettings
      });
      MenuBar.addAction(copyDisplay, 'tools');
      state.actions.push(copyDisplay);

      var pasteDisplay = new Action('mineplus_paste_display', {
        name: 'Mineplus: Paste Display Settings',
        icon: 'icon-paste',
        category: 'edit',
        click: pasteDisplaySettings
      });
      MenuBar.addAction(pasteDisplay, 'tools');
      state.actions.push(pasteDisplay);

      log('onload: 4 actions registered under Tools');

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
