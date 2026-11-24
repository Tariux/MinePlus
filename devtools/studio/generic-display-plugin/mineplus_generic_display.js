/* Mineplus Generic Display — Blockbench plugin.
 * Edits a .bbmodel's top-level "display" object in place, one vanilla
 * context at a time. Written values are copied verbatim by the Mineplus
 * pack compiler; nothing is derived from geometry. */
(function () {
  var CONTEXTS = [
    'thirdperson_righthand', 'thirdperson_lefthand',
    'firstperson_righthand', 'firstperson_lefthand',
    'head', 'gui', 'ground', 'fixed'
  ];
  var LIMITS = { rotation: 180, translation: 80, scale: 100 };
  var SCALE_MIN = 0.01;

  function clamp(value, bound) {
    if (typeof value !== 'number' || isNaN(value)) return 0;
    return Math.max(-bound, Math.min(bound, value));
  }

  function model() {
    return typeof Model !== 'undefined' ? Model : undefined;
  }

  function displayBlock() {
    var m = model();
    if (!m) return {};
    if (!m.display || typeof m.display !== 'object') m.display = {};
    return m.display;
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
    Canvas.updateAll();
  }

  function clearContext(context) {
    var block = displayBlock();
    delete block[context];
    Canvas.updateAll();
  }

  function nudge(context, field, axis, delta) {
    var entry = displayBlock()[context] || {};
    var current = entry[field] || (field === 'scale' ? [1, 1, 1] : [0, 0, 0]);
    var next = current.slice();
    next[axis] += delta;
    writeContext(context, (function () { var f = {}; f[field] = next; return f; })());
  }

  function openEditor() {
    if (!model()) {
      Blockbench.showQuickMessage('Open a .bbmodel first');
      return;
    }
    var dialog = new Blockbench.Dialog({
      title: 'Mineplus Display Editor',
      id: 'mineplus_display_editor',
      form: {
        context: { label: 'Context', type: 'select', options: CONTEXTS },
        rotation: { label: 'Rotation XYZ (deg, ±180)', type: 'text', placeholder: '0, 0, 0' },
        translation: { label: 'Translation XYZ (px, ±80)', type: 'text', placeholder: '0, 0, 0' },
        scale: { label: 'Scale XYZ (0.01–100)', type: 'text', placeholder: '1, 1, 1' }
      },
      onAccept: function (form) {
        function parse(text, fallback) {
          if (!text) return null;
          var parts = text.split(',').map(function (s) { return parseFloat(s.trim()); });
          if (parts.length !== 3 || parts.some(isNaN)) return null;
          return parts;
        }
        var fields = {};
        var rot = parse(form.rotation);
        var tra = parse(form.translation);
        var sca = parse(form.scale);
        if (rot) fields.rotation = rot;
        if (tra) fields.translation = tra;
        if (sca) fields.scale = sca;
        writeContext(form.context, fields);
      }
    });
    dialog.show();

    new Blockbench.Action({
      id: 'mineplus_clear_context',
      name: 'Clear this context',
      click: function () {
        var sel = document.querySelector('#mineplus_display_editor select');
        if (sel) clearContext(sel.value);
      }
    });
  }

  Plugin.register('mineplus_generic_display', {
    title: 'Mineplus Generic Display',
    author: 'Mineplus',
    version: '1.0.0',
    min_version: '4.0.0',
    tags: ['mineplus', 'display', 'minecraft'],
    onload: function () {
      new Action('mineplus_display_editor', {
        name: 'Mineplus Display Editor',
        category: 'edit',
        click: openEditor
      });
      // manual JSON path for power users
      Blockbench.on('bbmodel_saved', function () { /* display persists on the model */ });
    },
    onunload: function () {
      if (typeof mineplus_display_editor !== 'undefined') mineplus_display_editor.delete();
    }
  });
})();
