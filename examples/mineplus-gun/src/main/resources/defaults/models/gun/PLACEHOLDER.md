# Weapon model assets — local only

The `.bbmodel` geometry, `.png` textures and `.meta.json` overrides in this
folder are **not tracked in git** (see the module `.gitignore`). They are large
binary authoring files, kept in the author's library and copied here for local
builds.

To build the module with real weapons, place one file pair per weapon in this
folder, using the stem named by `config.yml`:

```
<stem>.bbmodel
<stem>.png
```

For example, `rifle-ak-gun-05.bbmodel` + `rifle-ak-gun-05.png` for the
`model: gun/rifle-ak-gun-05` entry. On enable the module installs them into the
Core's `models/gun/` folder as `models/gun/<stem>.bbmodel` + `.png`.

Without them the module still enables, registers items and runs the shop; the
weapons simply render as their vanilla backing material and their models resolve
as an empty geometry set.
