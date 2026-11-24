/* Mineplus Generic Display — Blockbench plugin v5.1.0.
 *
 * Tools (Tools menu, all logged):
 *   - Mineplus: Toggle Tools         show/hide all other Mineplus tools
 *                                    (state is remembered across sessions)
 *   - Mineplus: Convert to Mineplus Model
 *                                    migrate the open model (old bbmodel,
 *                                    java_block, free, …) into the mineplus
 *                                    format — cubes, bones, textures, DISPLAY
 *                                    and ANIMATIONS all survive, so the
 *                                    Animate tab and the display editor both
 *                                    work afterwards
 *   - Mineplus Display Editor        edit rotation/translation/scale per context
 *   - Mineplus: Copy Display         copy the display block as JSON
 *   - Mineplus: Validate Display     display-only validation
 *   - Mineplus: Validate Model       full Mine Plus compatibility check
 *                                    (budgets, 22.5° angles, textures, animations)
 *   - Mineplus: Template Settings    stamp the model as a Mine Plus template
 *                                    (the top-level "mineplus" metadata block)
 *   - Mineplus: Check Template       is this file detected as a Mine Plus template?
 *   - Mineplus: Save as .mpmodel     save in the Mineplus format
 *   - Mineplus: Export as .bbmodel   save a plain bbmodel (also fully loadable)
 *   - Mineplus: Open Generic / Java Item Model
 *                                    start a vanilla Java Item project or open
 *                                    a .json/.bbmodel/.mpmodel file
 *   - Mineplus: Fix Duplicates       remove accidentally duplicated cubes/groups
 *                                    (merge/import) and rename same-name elements
 *   - Mineplus: Model Report         cubes/bones/textures/animations summary
 *   - Mineplus: Show Log             in-app log panel
 *
 * Formats: registers the `mineplus_model` format (.mpmodel), bound to the
 * built-in project codec (Codecs.project) so .mpmodel saves, loads and
 * re-saves exactly like a .bbmodel — a full model, no conversion. Plain
 * .bbmodel files open natively and can be stamped with Template Settings;
 * .mpmodel files carry the stamp from the start. The stamp travels through a
 * registered ModelProject property (`mineplus`), so Blockbench itself writes
 * and reads it on every save/load of either format. The Mine Plus server
 * plugin loads both, with display and animations.
 *
 * Duplication debug trail: the merge_project and edit_texture events log
 * cube/bone counts, so a log from a session that showed the "changed a texture
 * and cubes/groups were copied" bug pinpoints the exact step.
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
  // Soft budgets enforced server-side by ModelAssetValidator (pack compile).
  var BUDGETS = {
    cubes: 512, bones: 128, animations: 64,
    textureEdge: 2048, sourceBytes: 2 * 1024 * 1024
  };
  // Clients below 1.21.6 reject whole item models whose element rotations are
  // off the 22.5° grid or beyond ±45° (see .mlai/DECISIONS.md).
  var ANGLE_STEP = 22.5;
  var ANGLE_MAX = 45;

  var state = { actions: [], hooks: [], logLines: [], property: null, addedExtension: false };
  var TOOLS_VISIBLE_KEY = 'mineplus_tools_visible';

  /* ------------------------------------------------------- tools visibility */

  function toolsVisible() {
    try {
      var stored = localStorage.getItem(TOOLS_VISIBLE_KEY);
      return stored === null ? true : stored === '1';
    } catch (e) {
      return state.toolsVisible !== false;
    }
  }

  function setToolsVisible(visible) {
    state.toolsVisible = visible;
    try { localStorage.setItem(TOOLS_VISIBLE_KEY, visible ? '1' : '0'); } catch (e) { /* private mode */ }
    log('tools ' + (visible ? 'shown' : 'hidden')
        + ' — run "Mineplus: Toggle Tools" (or reload the plugin) to switch back');
    Blockbench.showQuickMessage('Mineplus tools ' + (visible ? 'shown' : 'hidden'));
    if (typeof Canvas !== 'undefined' && Canvas.updateViewMode) Canvas.updateViewMode();
  }

  function toggleTools() {
    setToolsVisible(!toolsVisible());
  }

  // Condition on every Mineplus menu entry except the toggle itself: Blockbench
  // evaluates it each time the Tools menu opens, so hiding is instant and the
  // toggle stays reachable.
  function visibilityCondition() {
    return function () { return toolsVisible(); };
  }

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

  // The open project. Blockbench exposes it as the global `Project`
  // (mirrored on Blockbench.Project); some very old builds used a `model`
  // global instead. A truthy check is not enough on modern builds where
  // Blockbench.Project is 0 when nothing is open, so require a uuid.
  function currentModel() {
    if (typeof Project !== 'undefined' && Project && Project.uuid) return Project;
    if (typeof Blockbench !== 'undefined' && Blockbench.Project
        && Blockbench.Project.uuid) return Blockbench.Project;
    if (typeof Model !== 'undefined' && Model && Model.uuid) return Model;
    return null;
  }

  function displayBlock() {
    var model = currentModel();
    if (!model) return {};
    if (!model.display || typeof model.display !== 'object') model.display = {};
    return model.display;
  }

  function templateBlock() {
    var model = currentModel();
    if (!model) return null;
    if (!model.mineplus || typeof model.mineplus !== 'object') model.mineplus = {};
    return model.mineplus;
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

  /* ----------------------------------------------------------- validation */

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
                + ' exceeds ±' + LIMITS[field]
                + ' (the bake clamps it, author it inside the range)');
          } else if (field === 'scale' && Math.abs(component) < SCALE_MIN) {
            problems.push(context + '.scale[' + axis + '] = ' + component
                + ' below ' + SCALE_MIN + ' (would collapse the model)');
          }
        });
      });
    });
    if (problems.length) {
      warn('display validation found ' + problems.length + ' problem(s): ' + problems.join('; '));
    } else {
      log('display validation clean (' + Object.keys(block).length + ' context(s))');
    }
    return problems;
  }

  // Elements rotated on more than one axis, or beyond ±45°, cannot survive the
  // 22.5° snap applied for clients below 1.21.6 (ModelGeometryGuard).
  function snapRisk(rotation) {
    if (!Array.isArray(rotation)) return null;
    var axes = rotation.filter(function (a) { return a !== 0; }).length;
    if (axes > 1) return 'rotated on more than one axis';
    if (rotation.some(function (a) { return Math.abs(a) > ANGLE_MAX; })) {
      return 'rotated beyond ±' + ANGLE_MAX + '°';
    }
    var off = rotation.some(function (a) { return a !== 0 && (a / ANGLE_STEP) % 1 !== 0; });
    if (off) return 'off the ' + ANGLE_STEP + '° grid';
    return null;
  }

  function cubeList() {
    var model = currentModel();
    if (!model || !model.elements) return [];
    return model.elements.filter(function (el) {
      return typeof Cube === 'function' ? el instanceof Cube : true;
    });
  }

  function boneList() {
    var model = currentModel();
    if (!model || !model.groups) return [];
    return model.groups.filter(function (g) { return !!g.export; });
  }

  function validateModel() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return [];
    }
    log('validate model: starting full Mine Plus compatibility check');
    var problems = validateDisplay();

    var cubes = cubeList();
    var bones = boneList();
    if (cubes.length > BUDGETS.cubes) {
      problems.push(cubes.length + ' cubes exceed the soft budget of '
          + BUDGETS.cubes + ' (packets scale with cubes)');
    }
    if (bones.length > BUDGETS.bones) {
      problems.push(bones.length + ' bones exceed the soft budget of ' + BUDGETS.bones);
    }
    var snapCases = 0;
    cubes.forEach(function (cube) {
      if (cube.rotation && snapRisk(cube.rotation)) snapCases++;
    });
    if (snapCases) {
      problems.push(snapCases + ' cube(s) ' + snapRisk([90]) + '-class rotations'
          + ' — off-grid or multi-axis angles are snapped to ' + ANGLE_STEP
          + '° for clients below 1.21.6 (check any cube you want exact)');
    }
    (model.textures || []).forEach(function (tex) {
      var w = tex.width || 0, h = tex.height || 0;
      if (w > BUDGETS.textureEdge || h > BUDGETS.textureEdge) {
        problems.push('texture "' + (tex.name || tex.id) + '" is ' + w + 'x' + h
            + ' px; the soft budget edge is ' + BUDGETS.textureEdge + ' px');
      }
    });
    var animations = model.animations ? Object.keys(model.animations) : [];
    if (animations.length > BUDGETS.animations) {
      problems.push(animations.length + ' animations exceed the soft budget of '
          + BUDGETS.animations);
    }
    if (templateBlock() && !templateStampProblems().length) {
      log('template stamp present and well-formed');
    } else if (templateBlock() && templateStampProblems().length) {
      problems = problems.concat(templateStampProblems());
    } else {
      problems.push('not stamped as a Mine Plus template — run "Mineplus: Template Settings"'
          + ' so pack tools and the MCP server detect it');
    }
    if (problems.length) {
      warn('model validation found ' + problems.length + ' problem(s): '
          + problems.join('; '));
      Blockbench.showQuickMessage(problems.length + ' problem(s) — see log');
    } else {
      log('model validation clean: fully matches the Mine Plus format');
      Blockbench.showQuickMessage('Model fully matches the Mine Plus format');
    }
    return problems;
  }

  /* ---------------------------------------------------------- template */

  function templateStampProblems() {
    var stamp = templateBlock();
    if (!stamp) return [];
    var problems = [];
    if (!stamp.template) {
      problems.push('template stamp: "template" id is missing (e.g. "mineplus:guns/peacemaker")');
    } else if (!/^[\w./-]+:[\w./-]+$/.test(stamp.template)) {
      problems.push('template stamp: "' + stamp.template
          + '" is not a namespaced id like "mineplus:guns/peacemaker"');
    }
    var kinds = ['item', 'block', 'mob', 'generic'];
    if (!stamp.kind) {
      problems.push('template stamp: "kind" is missing (one of ' + kinds.join(', ') + ')');
    } else if (kinds.indexOf(stamp.kind) < 0) {
      problems.push('template stamp: kind "' + stamp.kind + '" is not one of ' + kinds.join(', '));
    }
    return problems;
  }

  function templateSettings() {
    if (!currentModel()) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var stamp = templateBlock();
    var dialog = new Dialog({
      title: 'Mineplus Template Settings',
      id: 'mineplus_template_dialog',
      form: {
        template: {
          label: 'Template id (namespaced)', type: 'text',
          placeholder: 'mineplus:guns/peacemaker', value: stamp.template || ''
        },
        kind: {
          label: 'Kind', type: 'select', value: stamp.kind || 'item',
          options: { item: 'item', block: 'block', mob: 'mob', generic: 'generic' }
        },
        title: {
          label: 'Human title', type: 'text',
          placeholder: 'Peacemaker', value: stamp.title || ''
        }
      },
      buttons: ['Apply', 'Clear stamp', 'Cancel'],
      onButton: function (button) {
        var values = formValues(dialog.form);
        if (button === 1) {
          var model = currentModel();
          model.mineplus = {
            format: 1,
            plugin: PLUGIN_ID,
            template: (values.template ? String(values.template) : '').trim(),
            kind: values.kind ? String(values.kind) : 'item',
            title: (values.title ? String(values.title) : '').trim()
          };
          if (model.mineplus.title === '') delete model.mineplus.title;
          log('template stamp written: ' + JSON.stringify(model.mineplus));
          var problems = templateStampProblems();
          if (problems.length) {
            warn(problems.join('; '));
            Blockbench.showQuickMessage('Stamp saved with problem(s) — see log');
          } else {
            Blockbench.showQuickMessage('Mine Plus template stamped');
          }
        } else if (button === 2) {
          var m = currentModel();
          delete m.mineplus;
          log('template stamp cleared');
        }
      }
    });
    dialog.show();
  }

  function checkTemplate() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var stamp = templateBlock();
    if (!stamp || !stamp.template) {
      log('check template: no well-formed "mineplus" block — this file will NOT'
          + ' be detected as a Mine Plus template');
      Blockbench.showQuickMessage('Not detected as a Mine Plus template — stamp it first');
      return;
    }
    var problems = templateStampProblems();
    log('check template: ' + JSON.stringify(stamp));
    log('check template: ' + (problems.length
        ? 'problems: ' + problems.join('; ')
        : 'detectable: format v' + (stamp.format || 1) + ', id "' + stamp.template
          + '", kind ' + stamp.kind + ' — the server plugin logs it on import and'
          + ' the devtools MCP server reports it as a Mine Plus template'));
    Blockbench.showQuickMessage(problems.length
        ? 'Template stamp has problem(s) — see log'
        : 'Detected as Mine Plus template "' + stamp.template + '"');
  }

  /* --------------------------------------------------------------- tools */

  function parseTriple(text) {
    if (!text) return null;
    var parts = text.split(',').map(function (s) { return parseFloat(s.trim()); });
    if (parts.length !== 3 || parts.some(isNaN)) return null;
    return parts;
  }

  // Read a finished dialog's values. In current Blockbench (5.x) `dialog.form`
  // is an InputForm object (js/interface/form.ts): the raw `{key: value}` map
  // is `form.getResult()` — so `form.rotation.value` reads undefined and every
  // `onButton` that reads it throws "Cannot read properties of undefined
  // (reading 'value')" (the reported crash at editor button 2). Prefer
  // getResult() and fall back to the legacy raw field map of older builds.
  function formValues(form) {
    var out = {};
    if (!form) return out;
    if (typeof form.getResult === 'function') {
      try {
        var result = form.getResult();
        if (result && typeof result === 'object') return result;
      } catch (failure) {
        warn('form.getResult() threw: ' + (failure && failure.message));
      }
    }
    Object.keys(form).forEach(function (key) {
      var field = form[key];
      if (field && typeof field === 'object' && 'value' in field) out[key] = field.value;
    });
    return out;
  }

  function openEditor() {
    log('display editor opened for "' + (currentModel()
        ? currentModel().name : '(no model)') + '"');
    if (!currentModel()) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var block = displayBlock();
    var context = CONTEXTS.filter(function (c) { return block[c]; })[0] || CONTEXTS[0];
    var entry = block[context] || {};
    var dialog = new Dialog({
      title: 'Mineplus Display Editor',
      id: 'mineplus_display_editor_dialog',
      form: {
        context: { label: 'Context', type: 'select', options: CONTEXTS, value: context },
        rotation: {
          label: 'Rotation XYZ (deg, ±180)', type: 'text',
          placeholder: '0, 0, 0', value: entry.rotation ? entry.rotation.join(', ') : ''
        },
        translation: {
          label: 'Translation XYZ (px, ±80)', type: 'text',
          placeholder: '0, 0, 0', value: entry.translation ? entry.translation.join(', ') : ''
        },
        scale: {
          label: 'Scale XYZ (0.01–100)', type: 'text',
          placeholder: '1, 1, 1', value: entry.scale ? entry.scale.join(', ') : ''
        }
      },
      buttons: ['Apply', 'Clear context', 'Cancel'],
      onButton: function (button) {
        log('editor button ' + button);
        var values = formValues(dialog.form);
        if (button === 1) {
          var fields = {};
          var rot = parseTriple(values.rotation);
          var tra = parseTriple(values.translation);
          var sca = parseTriple(values.scale);
          if (rot) fields.rotation = rot;
          if (tra) fields.translation = tra;
          if (sca) fields.scale = sca;
          writeContext(values.context, fields);
        } else if (button === 2) {
          clearContext(values.context);
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

  function saveAs(saveFormat) {
    if (!currentModel()) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var problems = validateDisplay();
    if (problems.length) {
      Blockbench.showQuickMessage(problems.length + ' display problem(s) — see log');
    }
    var isMp = saveFormat === 'mpmodel';
    var path = Blockbench.pickPath({
      type: 'save',
      name: (currentModel().name || 'model') + '.' + saveFormat,
      filter: isMp ? 'Mineplus Model (*.mpmodel)' : 'Blockbench Model (*.bbmodel)'
    });
    if (!path) {
      log('.' + saveFormat + ' save cancelled');
      return;
    }
    currentModel().extension = saveFormat;
    currentModel().saveFile = path;
    currentModel().saved = false;
    currentModel().export();
    log('.' + saveFormat + ' saved to ' + path
        + ' (with ' + problems.length + ' display validation problem(s))');
  }

  /* -------------------------------------------------- generic/java models */

  // Open a plain vanilla Java block/item model (or any bbmodel/mpmodel) and
  // start a fresh Java Item project — both keep working inside Mine Plus,
  // since the pack compiler reads authored display and cubes from either.
  function openGenericModel() {
    log('open generic/java-item: chooser shown');
    new Dialog({
      title: 'Mineplus: Open Generic / Java Item Model',
      id: 'mineplus_open_generic_dialog',
      lines: [
        'Start a new Java Block/Item model project (vanilla format), or open'
            + ' an existing .json (vanilla item/block model), .bbmodel or'
            + ' .mpmodel file.<br><br>'
            + 'Any model opened this way works with the Mine Plus plugin:'
            + ' stamp it with Template Settings to mark it as a Mine Plus'
            + ' template.'
      ],
      buttons: ['New Java Item model', 'Open model file…', 'Cancel'],
      onButton: function (button) {
        log('open-generic button ' + button);
        if (button === 0) startJavaItemProject();
        if (button === 1) pickAndOpenModel();
      }
    }).show();
  }

  function startJavaItemProject() {
    var format = typeof Formats !== 'undefined' && Formats.java_block
        ? Formats.java_block : null;
    if (!format || typeof newProject !== 'function') {
      warn('Java Block/Item format or newProject() missing in this build');
      Blockbench.showQuickMessage('This Blockbench build has no Java Block/Item format');
      return;
    }
    log('new project: Java Block/Item Model (vanilla generic format)');
    format.select();
    newProject(format);
    var model = currentModel();
    if (model && !model.mineplus) {
      model.mineplus = {
        template: 'mineplus-generic',
        kind: 'java_item',
        plugin: PLUGIN_ID,
        format: 1
      };
      log('new Java Item project stamped as Mine Plus generic template');
    }
    Blockbench.showQuickMessage('New Java Item model — edit display, then save as .mpmodel');
  }

  function pickAndOpenModel() {
    if (typeof loadModelFile !== 'function') {
      warn('loadModelFile missing in this build; use File > Open model instead');
      Blockbench.showQuickMessage('Use File > Open Model in this build');
      return;
    }
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,.bbmodel,.mpmodel';
    input.onchange = function () {
      var file = input.files && input.files[0];
      if (!file) {
        log('open model file: cancelled');
        return;
      }
      var reader = new FileReader();
      reader.onload = function () {
        log('opening "' + file.name + '" (' + reader.result.length + ' chars)'
            + ' through the matching Blockbench codec');
        try {
          loadModelFile({ content: reader.result, path: file.name, name: file.name }, {});
        } catch (failure) {
          warn('open failed: ' + (failure && failure.message));
        }
      };
      reader.onerror = function () { warn('file read failed for ' + file.name); };
      reader.readAsText(file);
    };
    input.click();
  }

  /* ------------------------------------------------------- duplicate repair */

  // Repair tool for accidentally duplicated cubes/groups (for example after a
  // drag-drop import or a merge). It removes exact UUID duplicates and renames
  // same-name elements instead of deleting anything unique — compatibility
  // only, no structural changes.
  function fixDuplicates() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var seen = {}, removedCubes = 0, removedBones = 0, renamed = 0;
    var usedNames = {};
    boneList().forEach(function (b) { usedNames[b.name] = true; });

    cubeList().slice().forEach(function (cube) {
      if (seen[cube.uuid]) {
        cube.remove();
        removedCubes++;
        return;
      }
      seen[cube.uuid] = true;
      if (usedNames[cube.name] && cube.name) {
        var i = 2;
        while (usedNames[cube.name + '.' + i]) i++;
        cube.name = cube.name + '.' + i;
        usedNames[cube.name] = true;
        renamed++;
      } else if (cube.name) {
        usedNames[cube.name] = true;
      }
    });
    seen = {};
    boneList().slice().forEach(function (bone) {
      if (seen[bone.uuid]) {
        bone.remove();
        removedBones++;
        return;
      }
      seen[bone.uuid] = true;
    });
    log('duplicate repair: removed ' + removedCubes + ' duplicate cube(s), '
        + removedBones + ' duplicate bone(s), renamed ' + renamed
        + ' same-name element(s); '
        + cubeList().length + ' cubes and ' + boneList().length
        + ' bones remain');
    Blockbench.showQuickMessage('Repair done — see log (undo with Ctrl+Z)');
  }

  function modelReport() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var cubes = cubeList();
    var bones = boneList();
    var textures = model.textures || [];
    var animations = model.animations ? Object.keys(model.animations) : [];
    var contexts = CONTEXTS.filter(function (c) { return displayBlock()[c]; });
    var lines = [
      'Cubes: ' + cubes.length + ' (budget ' + BUDGETS.cubes + ')',
      'Bones: ' + bones.length + ' (budget ' + BUDGETS.bones + ')',
      'Textures: ' + textures.length,
      'Animations: ' + animations.length + ' (budget ' + BUDGETS.animations + ')'
          + (animations.length ? ' — ' + animations.join(', ') : ''),
      'Display contexts authored: ' + contexts.length
          + (contexts.length ? ' — ' + contexts.join(', ') : ' (vanilla defaults apply)'),
      'Template: ' + (templateBlock() && templateBlock().template
          ? templateBlock().template + ' (' + templateBlock().kind + ')'
          : 'not stamped')
    ];
    log('model report:\n  ' + lines.join('\n  '));
    new Dialog({
      title: 'Mineplus Model Report',
      id: 'mineplus_report_dialog',
      lines: [lines.map(function (l) { return l + '<br>'; }).join('')]
    }).show();
  }

  /* -------------------------------------------------------------- format */

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
          + ' .mpmodel registration skipped, display tools still work');
      return;
    }
    new ModelFormat({
      id: MP_FORMAT_ID,
      name: 'Mineplus Model',
      description: 'Mineplus template format — a standard bbmodel plus the'
          + ' "mineplus" metadata block. Save with the .mpmodel extension.',
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
    // .mpmodel is a full bbmodel: let the built-in project codec compile and
    // parse it. Without this the format has no codec and save/export breaks. Without this the format has no codec and save/export breaks.
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
          + ' project codec (save/load via Codecs.project)');
    } else {
      log('format ' + MP_FORMAT_ID + ' registered (.mpmodel), but Codecs.project'
          + ' is unavailable — the format has no codec in this build');
    }
  }

  // Serialization: `mineplus` is a registered ModelProject property, so
  // Blockbench itself writes it into every saved bbmodel/mpmodel on compile
  // (bbmodel.js: Property.copy loop) and reads it back on load (merge loop).
  // This replaces the old approach of pushing into `Codecs.project`, which is
  // a single Codec object, not an array — that push never ran.
  function registerCodec() {
    if (typeof ModelProject === 'function' && typeof Property === 'function') {
      ModelProject.properties = ModelProject.properties || {};
      if (ModelProject.properties.mineplus) {
        log('property ModelProject.mineplus already registered; skipping');
      } else {
        state.property = new Property(ModelProject, 'object', 'mineplus', {
          exposed: false,
          default: {}
        });
        log('property ModelProject.mineplus registered — the template stamp is'
            + ' saved into .bbmodel and .mpmodel files and restored on load');
      }
    } else {
      warn('ModelProject/Property missing in this build; the stamp is set on'
          + ' the model but only "Template Settings" re-stamps after reload');
    }
    // Belt and braces: at the very end of compile the save_project event fires
    // with the finished model object — drop empty stamps there.
    hook('save_project', function (data) {
      var model = data && data.model;
      if (model && model.mineplus && !Object.keys(model.mineplus).length) {
        delete model.mineplus;
        log('save_project: removed empty mineplus block');
      }
    });
    log('save_project hook registered (empty-stamp cleanup)');
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
    try {
      Blockbench.on(event, wrapped);
    } catch (failure) {
      warn('hook ' + event + ' could not register: ' + (failure && failure.message));
      return;
    }
    state.hooks.push({ event: event, handler: wrapped });
    log('hook ' + event + ' registered');
  }

  // After a load, surface the template stamp if the event fires with the raw
  // model object (Blockbench versions differ; the file itself always keeps it).
  function restoreStamp(data) {
    var model = currentModel();
    if (!model) return;
    var stamp = data && data.mineplus ? data.mineplus
        : (data && data.model && data.model.mineplus ? data.model.mineplus : null);
    if (stamp && !model.mineplus) {
      model.mineplus = stamp;
      log('loaded template stamp: ' + JSON.stringify(stamp));
    } else if (!model.mineplus) {
      log('loaded a model without a template stamp (plain bbmodel or older save)'
          + ' — "Mineplus: Convert to Mineplus Model" migrates it in one step');
    }
    var animations = model.animations ? Object.keys(model.animations) : [];
    var animFiles = typeof Animation !== 'undefined' && model.animations
        && model.animations.length !== undefined ? model.animations.length : animations.length;
    log('loaded model "' + model.name + '": format ' + (model.format && model.format.id
        ? model.format.id : '(unknown)')
        + ', ' + animations.length + ' animation(s) in project'
        + ', ' + animFiles + ' in animations registry'
        + ', display contexts: ' + CONTEXTS.filter(function (c) {
          return model.display && model.display[c];
        }).length + ' — if the Animate tab is still missing, run'
        + ' "Mineplus: Convert to Mineplus Model"');
  }

  /* -------------------------------------------------------- display panel */

  // The Display panel (Panel 'display') has `condition: {modes: ['display']}`
  // (js/display_mode/display_mode.js:1717): it renders its slot list and the
  // rotation/translation/scale sliders ONLY while the display *mode* is
  // active — and that mode's tab requires `Format.display_mode` (line 1590).
  // Opening the panel from View > Panels while still in edit mode therefore
  // shows a completely empty column — the reported bug. Repair = re-run the
  // native chain with default tools only: enter display mode, bind the vue,
  // init display_settings, re-load the slot.
  function repairDisplayPanel() {
    var model = currentModel();
    if (!model) return;
    try {
      // 1. The panel is empty whenever display mode is not the active mode.
      if (typeof Modes !== 'undefined' && Modes.options && Modes.options.display) {
        if (typeof Mode === 'undefined' || !Mode || Mode.id !== 'display') {
          var displayFormat = globalFormat();
          if (displayFormat && displayFormat.display_mode === true
              && typeof Modes.options.display.select === 'function') {
            Modes.options.display.select();
            log('repairDisplayPanel: entered display mode — panel now populated');
          } else {
            log('repairDisplayPanel: display mode not available for format "'
                + (displayFormat && displayFormat.id) + '" — run Convert first');
          }
        }
      }
    } catch (failure) {
      warn('repairDisplayPanel: entering display mode failed: '
          + (failure && failure.message));
    }
    if (typeof DisplayMode === 'undefined' || !DisplayMode) {
      warn('repairDisplayPanel: no DisplayMode object in this build');
      return;
    }
    try {
      // display_settings must exist for loadDisp to read the slot values.
      if (!model.display_settings) model.display_settings = {};
    } catch (failure) {
      warn('repairDisplayPanel: cannot init display_settings: '
          + (failure && failure.message));
    }
    try {
      if (!DisplayMode.vue && typeof Interface !== 'undefined' && Interface.Panels
          && Interface.Panels.display) {
        DisplayMode.vue = Interface.Panels.display.inside_vue;
        log('repairDisplayPanel: bound display panel vue (was missing)');
      }
    } catch (failure) {
      warn('repairDisplayPanel: panel vue binding failed: '
          + (failure && failure.message));
    }
    try {
      var slot = DisplayMode.display_slot || 'thirdperson_righthand';
      var bound = DisplayMode.vue;
      var hasSlot = bound && bound._data && bound._data.slot
          && bound._data.slot.slot_id;
      if (!hasSlot && typeof DisplayMode.load === 'function') {
        DisplayMode.load(slot);
        log('repairDisplayPanel: re-loaded display slot "' + slot
            + '" — panel sliders restored');
      }
    } catch (failure) {
      warn('repairDisplayPanel: slot re-load failed: '
          + (failure && failure.message));
    }
  }

  /* ------------------------------------------------------------ conversion */

  // Make `fmt` the ACTIVE format everywhere it matters. Tries, in order:
  // 1. fmt.select()            — the official switch (updates panels, mode bar)
  // 2. fmt.codec.select()      — some builds put select on the codec only
  // 3. global Format + model   — last resort, then ask Blockbench to refresh
  // Every step logs; one path succeeding is enough for the Display/Animate
  // tabs to follow the mineplus flags (display_mode + animation_mode both on).
  // The global active format — the thing the Display/Animate tabs actually
  // follow (Blockbench's page-scope `Format` var, mirrored on
  // `Blockbench.Format`). Never trust model.format alone for tab visibility.
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

  function selectMineplusFormat(fmt, model) {
    if (typeof fmt.select === 'function') {
      try {
        fmt.select();
        log('format selected via fmt.select()');
        return true;
      } catch (failure) {
        warn('fmt.select() threw: ' + (failure && failure.message));
      }
    } else {
      log('fmt.select() missing on this build — trying fallbacks');
    }
    if (fmt.codec && typeof fmt.codec.select === 'function') {
      try {
        fmt.codec.select();
        log('format selected via fmt.codec.select()');
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
      log('global Format assigned directly');
    } catch (failure) {
      warn('global Format assignment failed: ' + (failure && failure.message));
    }
    if (model && typeof model === 'object') model.format = fmt;
    try {
      if (typeof updateModeSelector === 'function') updateModeSelector();
      else if (typeof Blockbench !== 'undefined' && Blockbench.dispatchEvent) {
        Blockbench.dispatchEvent('update_mode_selector');
      }
      log('mode selector refresh dispatched');
    } catch (failure) {
      warn('mode selector refresh failed: ' + (failure && failure.message));
    }
    return false;
  }

  // Switch the open project to the mineplus format. Returns true on success.
  // `auto` marks silent calls from the load hook (kept out of quick messages).
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
      // may not point at mineplus yet, and the Display/Animate tabs follow the
      // GLOBAL format, not model.format. Skipping this left reopened models
      // with the old format's tabs (Animate but no Display).
      selectMineplusFormat(fmt, model);
      log('format already ' + MP_FORMAT_ID + ' — global selection re-applied');
      return true;
    }
    log('format switch: ' + (model.format && model.format.id ? model.format.id
        : '(unknown)') + ' → ' + MP_FORMAT_ID);
    // Preferred path: Blockbench's native ModelFormat.convertTo() — it also
    // migrates box UV, bone rig grouping (loose cubes get a root group) and
    // rotation snapping per format, which a bare select() skips and which old
    // .bbmodel files saved in generic/java formats need.
    if (typeof fmt.convertTo === 'function') {
      try {
        fmt.convertTo();
        log('format converted via native convertTo() (box UV, bone rig and'
            + ' rotation limits migrated)');
      } catch (failure) {
        warn('native convertTo() threw: ' + (failure && failure.message)
            + ' — falling back to plain select');
        selectMineplusFormat(fmt, model);
      }
    } else {
      // Format.select() is the only correct switch: it sets the global Format
      // (which drives the Display/Animate tabs via Format.display_mode /
      // Format.animation_mode), updates the mode selector and panels. A plain
      // `model.format = fmt` leaves the global stale — Animate stays from the
      // old format and the Display tab never appears.
      selectMineplusFormat(fmt, model);
    }
    // Verify the GLOBAL format actually landed on mineplus. The Animate and
    // Display tabs are gated by Format.animation_mode
    // (js/animations/animation_mode.js:923) and Format.display_mode
    // (js/display_mode/display_mode.js:1590). Old .bbmodel files load in the
    // generic 'free' format, which sets animation_mode but NOT display_mode —
    // that exact combination is the reported "Animate exists, no Display
    // tab" bug. If the native path did not land, fall back to select().
    var global = globalFormat();
    if (!global || global.id !== MP_FORMAT_ID) {
      warn('convert: global Format did not land on ' + MP_FORMAT_ID
          + ' — re-selecting');
      selectMineplusFormat(fmt, model);
      global = globalFormat();
    }
    if (global && global.display_mode === true && global.animation_mode === true) {
      log('tabs after convert: Animate ON, Display ON (global format '
          + MP_FORMAT_ID + ')');
    } else {
      warn('tab check after convert: global format '
          + (global && global.id ? global.id : '(none)')
          + ' animation_mode=' + !!(global && global.animation_mode)
          + ' display_mode=' + !!(global && global.display_mode)
          + ' — if Display is still missing, reload the plugin and retry');
    }
    model.mode = 'edit';
    var modes = typeof Modes !== 'undefined' ? Modes : null;
    var editMode = modes && (modes.edit || (modes.options && modes.options.edit));
    if (editMode && typeof editMode.select === 'function') editMode.select();
    else if (typeof Mode !== 'undefined' && Mode.edit) Mode.select(Mode.edit);
    // The Display panel only renders content in display mode (condition
    // {modes: ['display']}), so drop the designer straight into it with the
    // slot list and sliders visible — default Blockbench UI, no custom GUI.
    repairDisplayPanel();
    if (!model.mineplus) model.mineplus = { format: 1, plugin: PLUGIN_ID };
    if (typeof Canvas !== 'undefined' && Canvas.updateAll) Canvas.updateAll();
    log('format switched to ' + MP_FORMAT_ID
        + ' — project object untouched (cubes, bones, textures, display,'
        + ' animations all kept)');
    return true;
  }

  // Migrate ANY open model (an old .bbmodel saved in another format, a
  // java_block project, a free model, …) into the Mineplus format: cubes,
  // bones, textures, the authored display block and every animation are kept
  // untouched — only the project's format stamp changes, so both the Animate
  // tab and the display editor work afterwards.
  function convertToMineplus() {
    var model = currentModel();
    if (!model) {
      Blockbench.showQuickMessage('Open or create a model first');
      return;
    }
    var before = {
      cubes: cubeList().length,
      bones: boneList().length,
      textures: (model.textures || []).length,
      animations: model.animations ? Object.keys(model.animations).length : 0,
      display: Object.keys(displayBlock()).length
    };
    log('convert: starting from format ' + (model.format && model.format.id
        ? model.format.id : '(unknown)') + ' — ' + JSON.stringify(before));
    if (typeof Animation === 'undefined' || !Animation.stored) {
      warn('convert: Animation.stored missing — animations may not survive');
    }
    var ok = applyMineplusFormat(false);
    if (!ok) {
      Blockbench.showQuickMessage('Mineplus format unavailable — see log');
      return;
    }
    var after = {
      cubes: cubeList().length,
      bones: boneList().length,
      textures: (model.textures || []).length,
      animations: model.animations ? Object.keys(model.animations).length : 0,
      display: Object.keys(displayBlock()).length
    };
    var lost = Object.keys(before).filter(function (k) { return after[k] < before[k]; });
    if (lost.length) {
      warn('convert: some content count dropped: before ' + JSON.stringify(before)
          + ' after ' + JSON.stringify(after) + ' — undo (Ctrl+Z) and report the log');
    } else {
      log('convert: content intact ' + JSON.stringify(after)
          + ' — save as .mpmodel to keep it');
    }
    if (typeof Canvas !== 'undefined' && Canvas.updateAll) Canvas.updateAll();
    Blockbench.showQuickMessage(lost.length
        ? 'Converted with content changes — see log'
        : 'Converted to Mineplus Model — Animate tab and display editor enabled');
  }

  // Open an old .bbmodel (or any project file) and convert it in one step.
  function openAndMigrate() {
    if (typeof loadModelFile !== 'function') {
      warn('loadModelFile missing in this build; open the file with File > Open Model, then run Convert');
      Blockbench.showQuickMessage('Open the file first, then run Convert to Mineplus Model');
      return;
    }
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = '.bbmodel,.bbmodel.old,.json,.mpmodel';
    input.onchange = function () {
      var file = input.files && input.files[0];
      if (!file) {
        log('migrate open: cancelled');
        return;
      }
      var reader = new FileReader();
      reader.onload = function () {
        log('migrate open: loading "' + file.name + '" through its matching codec');
        try {
          loadModelFile({ content: reader.result, path: file.name, name: file.name }, {});
          log('migrate open: loaded — converting to Mineplus Model');
          setTimeout(function () { convertToMineplus(); }, 50);
        } catch (failure) {
          warn('migrate open failed: ' + (failure && failure.message));
        }
      };
      reader.onerror = function () { warn('file read failed for ' + file.name); };
      reader.readAsText(file);
    };
    input.click();
  }

  /* --------------------------------------------------------------- setup */

  function registerAction(id, name, icon, click) {
    var action = new Action(id, {
      name: name, icon: icon, category: 'edit',
      condition: visibilityCondition(), click: click
    });
    MenuBar.addAction(action, 'tools');
    state.actions.push(action);
    log('action ' + id + ' registered in Tools menu');
    return action;
  }

  Plugin.register(PLUGIN_ID, {
    title: 'Mineplus Generic Display',
    author: 'Mineplus',
    version: '5.1.0',
    min_version: '4.0.0',
    tags: ['mineplus', 'display', 'animation', 'minecraft', 'mpmodel', 'template'],
    onload: function () {
      log('onload: starting (v5.1.0)');
      // Each step is isolated: one failure (for example the format
      // constructor missing on a Blockbench build) logs a warning and the
      // remaining tools still register.
      [
        ['format .mpmodel', registerFormat],
        ['codec template stamp', registerCodec],
        ['action mineplus_toggle_tools', function () {
          var toggle = new Action('mineplus_toggle_tools', {
            name: 'Mineplus: Toggle Tools',
            icon: 'icon-explore',
            description: 'Show or hide all other Mineplus tools in this menu',
            click: toggleTools
          });
          MenuBar.addAction(toggle, 'tools');
          state.actions.push(toggle);
          log('action mineplus_toggle_tools registered in Tools menu (always visible)');
        }],
        ['action mineplus_display_editor', function () {
          registerAction('mineplus_display_editor', 'Mineplus Display Editor',
              'icon-configure', openEditor);
        }],
        ['action mineplus_convert', function () {
          registerAction('mineplus_convert', 'Mineplus: Convert to Mineplus Model',
              'icon-change_format', convertToMineplus);
        }],
        ['action mineplus_open_migrate', function () {
          registerAction('mineplus_open_migrate', 'Mineplus: Open & Migrate Old Model…',
              'icon-open_folder', openAndMigrate);
        }],
        ['action mineplus_copy_display', function () {
          registerAction('mineplus_copy_display', 'Mineplus: Copy Display',
              'icon-copy', copyDisplay);
        }],
        ['action mineplus_validate_display', function () {
          registerAction('mineplus_validate_display', 'Mineplus: Validate Display',
              'icon-verified', function () { validateDisplay(); });
        }],
        ['action mineplus_validate_model', function () {
          registerAction('mineplus_validate_model', 'Mineplus: Validate Model',
              'icon-verified_list', function () { validateModel(); });
        }],
        ['action mineplus_template_settings', function () {
          registerAction('mineplus_template_settings', 'Mineplus: Template Settings',
              'icon-bookmark', templateSettings);
        }],
        ['action mineplus_check_template', function () {
          registerAction('mineplus_check_template', 'Mineplus: Check Template',
              'icon-search', checkTemplate);
        }],
        ['action mineplus_save_mpmodel', function () {
          registerAction('mineplus_save_mpmodel', 'Mineplus: Save as .mpmodel',
              'icon-save', function () { saveAs('mpmodel'); });
        }],
        ['action mineplus_export_bbmodel', function () {
          registerAction('mineplus_export_bbmodel', 'Mineplus: Export as .bbmodel',
              'icon-export', function () { saveAs('bbmodel'); });
        }],
        ['action mineplus_open_generic', function () {
          registerAction('mineplus_open_generic', 'Mineplus: Open Generic / Java Item Model',
              'icon-open', openGenericModel);
        }],
        ['action mineplus_fix_duplicates', function () {
          registerAction('mineplus_fix_duplicates', 'Mineplus: Fix Duplicates',
              'icon-clone', fixDuplicates);
        }],
        ['action mineplus_model_report', function () {
          registerAction('mineplus_model_report', 'Mineplus: Model Report',
              'icon-chart', modelReport);
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
        repairDisplayPanel();
      });
      // Blockbench dispatches `select_mode` when a mode tab is clicked
      // (js/modes.ts:139); `display_mode_entered` is not a real event, so the
      // panel repair is wired to the event that actually fires.
      hook('select_mode', function (data) {
        var mode = data && data.mode;
        if (mode && mode.id === 'display') repairDisplayPanel();
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
      // Debug trail for the reported "changing a texture duplicates cubes and
      // groups" problem: both events log element counts, so the next log shows
      // exactly which step introduces the copies.
      hook('merge_project', function (data) {
        var model = currentModel();
        log('EVENT merge_project: model now has ' + cubeList().length + ' cube(s), '
            + boneList().length + ' bone(s)'
            + ' — if cubes were just duplicated, this merge is where it happened');
      });
      hook('edit_texture', function (data) {
        var model = currentModel();
        log('EVENT edit_texture: model now has ' + cubeList().length + ' cube(s), '
            + boneList().length + ' bone(s)');
      });
      // Load events differ across versions; register both and restore the
      // stamp from whichever fires with the raw model data.
      hook('load_project', restoreStamp);
      hook('project_loaded', restoreStamp);
      // After any load, make sure the mineplus format is active so the Animate
      // tab and the display editor are both available without a manual step.
      // Failures are LOGGED now — a silent catch hid this exact fix before.
      function autoApply() {
        try {
          applyMineplusFormat(true);
        } catch (failure) {
          warn('auto format apply threw: ' + (failure && failure.message));
        }
      }
      hook('project_loaded', autoApply);
      // Some builds finish building the UI after the load event; re-apply once
      // on the next tick so the global format (and the tabs) ends up correct.
      hook('load_project', function () { setTimeout(autoApply, 50); });
      hook('select_project', autoApply);
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
      if (state.property) {
        try {
          if (ModelProject.properties && ModelProject.properties.mineplus) {
            delete ModelProject.properties.mineplus;
            log('property ModelProject.mineplus removed');
          }
        } catch (e) { /* best effort */ }
      }
      if (state.addedExtension && typeof Codecs !== 'undefined' && Codecs.project
          && Codecs.project.load_filter && Codecs.project.load_filter.extensions) {
        var index = Codecs.project.load_filter.extensions.indexOf('mpmodel');
        if (index >= 0) {
          Codecs.project.load_filter.extensions.splice(index, 1);
          log('mpmodel extension removed from project codec');
        }
      }
      state.actions = [];
      state.hooks = [];
      state.property = null;
      state.addedExtension = false;
    },
    oninstall: function () { log('oninstall'); },
    onenabled: function () { log('onenabled'); },
    ondisabled: function () { log('ondisabled'); }
  });
})();
