/* Mineplus Generic Display — Blockbench plugin v2.
 *
 * Tools (Tools menu, all logged):
 *   - Mineplus Display Editor     edit rotation/translation/scale per context
 *   - Mineplus: Copy Display      copy the display block as JSON
 *   - Mineplus: Validate Display  report out-of-range/NaN values before save
 *   - Mineplus: Save as .mpmodel  save the open model in the Mineplus format
 *   - Mineplus: Show Log          in-app log panel
 *
 * Format: registers the `mineplus_model` format (extension .mpmodel). An
 * .mpmodel file is a standard bbmodel JSON plus an optional top-level
 * "mineplus" metadata block, so the Mineplus server plugin parses it with the
 * same streaming importer it uses for .bbmodel.
 *
 * Every step logs through [Mineplus] in the console (Console > Developer
 * Tools) and into the in-app log panel (Tools > Mineplus: Show Log).
 */
(function () {
  'use strict';

  var PLUGIN_ID = 'mineplus_generic_display';
  var LOG_PREFIX = '[Mineplus]';
  var CONTEXTS = [
    'thirdperson_righthand', 'thirdperson_lefthand',
    'firstperson_righthand', 'firstperson_lefthand',
    'head', 'gui', 'ground', 'fixed'
  ];
  var LIMITS = { rotation: 180, translation: 80, scale: 100 };
  var SCALE_MIN = 0.01;
  var MP_FORMAT_ID = 'mineplus_model';

  var state = { actions: [], hooks: [], logLines: [] };

  /* ------------------------------------------------------------------ log */

  function log(message) {
    var line = LOG_PREFIX + ' ' + message;
    state.logLines.push(line);
    if (state.logLines.length > 400) state.logLines.shift();
    console.log(line);
  }

  function warn(message) {
    log('WARN ' + message);
  }

  function showLog() {
    var text = state.logLines.length
        ? state.logLines.join('\n')
        : LOG_PREFIX + ' nothing logged yet.';
    new Dialog({
      title: 'Mineplus plugin log',
      id: 'mineplus_log_dialog',
      lines: [text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/\n/g, '<br>')]
    }).show();
  }

  /* ------------------------------------------------------- model helpers */

  function currentModel() {
    return (typeof Model !== 'undefined' && Model && Model.mesh) ? Model : null;
  }

  function displayBlock() {
    var model = currentModel();
    if (!model) return {};
    if (!model.display || typeof model.display !== 'object') model.display = {};
    return model.display;
  }

  function clamp(value, bound) {
    if (typeof value !== 'number' || isNaN(value)) return 0;
    return Math.max(-bound, Math.min(bound, value));
  }

  function writeContext(context, fields) {
    var block = displayBlock();
    var entry = block[context] || {};
    ['rotation', 'translation', 'scale'].forEach(function (field) {
      var v = fields[field];
      if (!v) return;
      var bound = LIMITS[field];
      var out = [clamp(v[0], bound), clamp(v[1], bound), clamp(v[2], bound)];
      if (field === 'scale') {
        out = out.map(function (x) { return Math.abs(x) < SCALE_MIN ? SCALE_MIN : x; });
      }
      entry[field] = out;
    });
    block[context] = entry;
    log('display["' + context + '"] written: ' + JSON.stringify(entry));
    if (typeof Canvas !== 'undefined' && Canvas.updateAll) Canvas.updateAll();
  }

  function clearContext(context) {
    var block = displayBlock();
    delete block[context];
    log('display["' + context + '"] cleared (vanilla defaults apply)');
    if (typeof Canvas !== 'undefined' && Canvas.updateAll) Canvas.updateAll();
  }

  function validateDisplay() {
    var block = displayBlock();
    var problems = [];
    CONTEXTS.forEach(function (context) {
      var entry = block[context];
      if (!entry) return;
      ['rotation', 'translation', 'scale'].forEach(function (field) {
        var v = entry[field];
        if (!v) return;
        v.forEach(function (component, axis) {
          if (typeof component !== 'number' || isNaN(component)) {
            problems.push(context + '.' + field + '[' + axis + '] is NaN/missing');
          } else if (Math.abs(component) > LIMITS[field]) {
            problems.push(context + '.' + field + '[' + axis + '] = ' + component
                + ' exceeds ±' + LIMITS[field]);
          } else if (field === 'scale' && Math.abs(component) < SCALE_MIN) {
            problems.push(context + '.scale[' + axis + '] = ' + component
                + ' below ' + SCALE_MIN + ' (would collapse the model)');
          }
        });
      });
    });
    if (problems.length) {
      warn('validation found ' + problems.length + ' problem(s): ' + problems.join('; '));
    } else {
      log('validation clean (' + Object.keys(block).length + ' context(s))');
    }
    return problems;
  }

  /* --------------------------------------------------------------- tools */

  function parseTriple(text) {
    if (!text) return null;
    var parts = text.split(',').map(function (s) { return parseFloat(s.trim()); });
    if (parts.length !== 3 || parts.some(isNaN)) return null;
    return parts;
  }

  function openEditor() {
    log('display editor opened for "' + (currentModel()
        ? currentModel().name : '(no model)') + '"');
    if (!currentModel()) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var dialog = new Dialog({
      title: 'Mineplus Display Editor',
      id: 'mineplus_display_editor_dialog',
      form: {
        context: { label: 'Context', type: 'select', options: CONTEXTS },
        rotation: { label: 'Rotation XYZ (deg, ±180)', type: 'text', placeholder: '0, 0, 0' },
        translation: { label: 'Translation XYZ (px, ±80)', type: 'text', placeholder: '0, 0, 0' },
        scale: { label: 'Scale XYZ (0.01–100)', type: 'text', placeholder: '1, 1, 1' }
      },
      buttons: ['Apply', 'Clear context', 'Cancel'],
      onButton: function (button) {
        log('editor button ' + button);
        var form = dialog.form;
        if (button === 1) {
          var fields = {};
          var rot = parseTriple(form.rotation && form.rotation.value);
          var tra = parseTriple(form.translation && form.translation.value);
          var sca = parseTriple(form.scale && form.scale.value);
          if (rot) fields.rotation = rot;
          if (tra) fields.translation = tra;
          if (sca) fields.scale = sca;
          writeContext(form.context.value, fields);
        } else if (button === 2) {
          clearContext(form.context.value);
        }
      }
    });
    dialog.show();
  }

  function copyDisplay() {
    var json = JSON.stringify(displayBlock(), null, 2);
    navigator.clipboard.writeText(json).then(function () {
      log('display copied to clipboard (' + json.length + ' chars)');
      Blockbench.showQuickMessage('Display block copied');
    }, function (failure) {
      warn('clipboard write failed: ' + failure);
    });
  }

  function saveAsMpmodel() {
    if (!currentModel()) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var problems = validateDisplay();
    if (problems.length) {
      Blockbench.showQuickMessage(problems.length + ' display problem(s) — see log');
    }
    var path = Blockbench.pickPath({
      type: 'save',
      name: (currentModel().name || 'model') + '.mpmodel',
      filter: 'Mineplus Model (*.mpmodel)'
    });
    if (!path) {
      log('.mpmodel save cancelled');
      return;
    }
    currentModel().extension = 'mpmodel';
    currentModel().saveFile = path;
    currentModel().saved = false;
    currentModel().export();
    log('.mpmodel saved to ' + path + ' (with ' + problems.length + ' validation problem(s))');
  }

  /* -------------------------------------------------------------- format */

  function allFormats() {
    // `ModelFormat.all` does not exist in every Blockbench build (5.2.2
    // desktop ships it undefined); fall back to the legacy `Formats` map.
    if (typeof ModelFormat !== 'undefined' && Array.isArray(ModelFormat.all)) {
      return ModelFormat.all;
    }
    if (typeof Formats !== 'undefined' && Formats) {
      return Object.keys(Formats).map(function (id) { return Formats[id]; });
    }
    return [];
  }

  function registerFormat() {
    if (typeof ModelFormat !== 'function') {
      warn('ModelFormat constructor not available in this Blockbench build;'
          + ' skipping .mpmodel format registration (display tools still work)');
      return;
    }
    var existing = allFormats().find(function (f) { return f && f.id === MP_FORMAT_ID; });
    if (existing) {
      log('format ' + MP_FORMAT_ID + ' already registered; skipping');
      return;
    }
    // An .mpmodel file is a bbmodel JSON plus our metadata block, so the
    // server-side streaming importer parses it unchanged.
    new ModelFormat({
      id: MP_FORMAT_ID,
      name: 'Mineplus Model',
      category: 'minecraft',
      description: 'Mineplus server plugin model: bbmodel JSON with an optional "mineplus" metadata block. Save with the .mpmodel extension.',
      extension: 'mpmodel',
      icon: 'icon-format_free',
      model_identifier: 'model',
      vertex_color: false,
      texture_meshes: false,
      animation_mode: true,
      display_mode: true,
      bone_rig: true,
      centered_grid: true
    });
    log('format ' + MP_FORMAT_ID + ' registered (.mpmodel)');
  }

  /* --------------------------------------------------------------- hooks */

  function hook(event, handler) {
    var wrapped = function (data) {
      try {
        handler(data);
      } catch (failure) {
        warn('hook ' + event + ' threw: ' + (failure && failure.message));
      }
    };
    Blockbench.on(event, wrapped);
    state.hooks.push({ event: event, handler: wrapped });
    log('hook ' + event + ' registered');
  }

  /* --------------------------------------------------------------- setup */

  function registerAction(id, name, icon, click) {
    var action = new Action(id, { name: name, icon: icon, click: click });
    MenuBar.addAction(action, 'tools');
    state.actions.push(action);
    log('action ' + id + ' registered in Tools menu');
    return action;
  }

  Plugin.register(PLUGIN_ID, {
    title: 'Mineplus Generic Display',
    author: 'Mineplus',
    version: '2.0.1',
    min_version: '4.0.0',
    tags: ['mineplus', 'display', 'minecraft', 'mpmodel'],
    onload: function () {
      log('onload: starting (v2.0.1)');
      // Each step is isolated: one failure (for example the format
      // constructor missing on a Blockbench build) logs a warning and the
      // remaining tools still register.
      [
        ['format .mpmodel', registerFormat],
        ['action mineplus_display_editor', function () {
          registerAction('mineplus_display_editor', 'Mineplus Display Editor',
              'icon-configure', openEditor);
        }],
        ['action mineplus_copy_display', function () {
          registerAction('mineplus_copy_display', 'Mineplus: Copy Display',
              'icon-copy', copyDisplay);
        }],
        ['action mineplus_validate_display', function () {
          registerAction('mineplus_validate_display', 'Mineplus: Validate Display',
              'icon-verified', function () { validateDisplay(); });
        }],
        ['action mineplus_save_mpmodel', function () {
          registerAction('mineplus_save_mpmodel', 'Mineplus: Save as .mpmodel',
              'icon-save', saveAsMpmodel);
        }],
        ['action mineplus_show_log', function () {
          registerAction('mineplus_show_log', 'Mineplus: Show Log',
              'icon-list', showLog);
        }]
      ].forEach(function (step) {
        try {
          step[1]();
        } catch (failure) {
          warn('could not register ' + step[0] + ': '
              + (failure && failure.message));
        }
      });
      hook('display_mode_entered', function () {
        log('display mode entered — edit contexts above the preview');
      });
      hook('display_mode_exited', function () {
        log('display mode exited; display block now: '
            + JSON.stringify(displayBlock()));
      });
      hook('project_saved', function (data) {
        log('project saved' + (data && data.path ? ' to ' + data.path : '')
            + '; validating display…');
        validateDisplay();
      });
      log('onload: all actions registered under Tools');
    },
    onunload: function () {
      log('onunload: removing ' + state.actions.length + ' action(s), '
          + state.hooks.length + ' hook(s)');
      state.actions.forEach(function (action) {
        try { MenuBar.removeAction(action); } catch (e) { /* already gone */ }
      });
      state.hooks.forEach(function (h) {
        try { Blockbench.off(h.event, h.handler); } catch (e) { /* already gone */ }
      });
      state.actions = [];
      state.hooks = [];
    },
    oninstall: function () { log('oninstall'); },
    onenabled: function () { log('onenabled'); },
    ondisabled: function () { log('ondisabled'); }
  });
})();
