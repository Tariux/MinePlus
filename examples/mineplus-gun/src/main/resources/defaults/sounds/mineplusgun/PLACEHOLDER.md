# Weapon sounds — local only

The `.ogg` samples in this folder are **not tracked in git** (see the module
`.gitignore`). `sounds.json` (one level up) **is** tracked: it declares the
additive `mineplusgun:` events the runtime plays.

The module expects these files here for a full build:

```
shot1.ogg shot2.ogg shot3.ogg shot4.ogg
reload_start.ogg reload_end.ogg empty.ogg
```

They are installed into the Core data folder and registered as
`assets/mineplusgun/sounds/<name>.ogg`. Without them the module still fires and
plays events, but the client has no sample to play (silent, never an error).
